package eu.chezmaurice.core.chat

import eu.chezmaurice.core.model.DataBlock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

// Reading the arbitrary JSON of a tool result, leniently.

operator fun JsonElement.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
fun JsonElement?.str(key: String): String = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString || it.intOrNull != null }?.content.orEmpty()
fun JsonElement?.int(key: String): Int? = (this?.get(key) as? JsonPrimitive)?.intOrNull
fun JsonElement?.bool(key: String): Boolean = (this?.get(key) as? JsonPrimitive)?.booleanOrNull == true
fun JsonElement?.array(key: String): List<JsonElement> = (this?.get(key) as? JsonArray).orEmpty()
fun JsonElement?.strings(key: String): List<String> = array(key).mapNotNull { (it as? JsonPrimitive)?.content }

/** A payload with a string `card` has a purpose-built view. */
val DataBlock.cardKind: String? get() = (data["card"] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The kinds drawn under the reply, as part of the answer (`DataCardStack.answerKinds`). */
val ANSWER_CARDS = setOf("candidates", "media", "fact", "review")

fun List<DataBlock>.answerCards(): List<DataBlock> = filter { it.cardKind in ANSWER_CARDS }

/**
 * The reply as shown: when a "review" card carries the footer, the footer
 * comes off the prose — the card says it better (`withoutReviewFooter`).
 */
fun withoutReviewFooter(content: String, blocks: List<DataBlock>): String {
    // The footer as the server wrote it, its last occurrence only; the rest trimmed at both ends.
    val footer = blocks.firstOrNull { it.cardKind == "review" }?.data?.str("footer").orEmpty()
    if (footer.isEmpty()) return content
    val at = content.lastIndexOf(footer)
    if (at < 0) return content
    return content.removeRange(at, at + footer.length).trim()
}

/**
 * "2 web searches, 1 memory search, 3 tool calls" — `TurnTally`. From the
 * steps when the turn was watched (they know every call, even those that
 * drew nothing); from the blocks alone for a thread read back.
 */
data class TurnTally(val web: Int = 0, val memory: Int = 0, val tools: Int = 0) {
    val isEmpty: Boolean get() = web + memory + tools == 0

    companion object {
        fun of(steps: List<ToolStep>, blocks: List<DataBlock>, doneOnly: Boolean = false): TurnTally {
            var web = 0; var memory = 0; var tools = 0
            if (steps.isEmpty()) {
                for (block in blocks) {
                    when (block.cardKind) {
                        "sources" -> if (block.data.str("origin") == "web") web++ else memory++
                        // A search that found nothing comes back bare; its tool says what it was.
                        null -> when (block.tool) {
                            "web_search", "read_web_page" -> web++
                            "corpus__search" -> memory++
                            else -> tools++
                        }
                        else -> {}
                    }
                }
            } else {
                for (step in steps) {
                    val n = step.count - if (doneOnly && step.running) 1 else 0
                    when (step.tool) {
                        "web_search", "read_web_page" -> web += n
                        "corpus__search" -> memory += n
                        else -> tools += n
                    }
                }
            }
            return TurnTally(web, memory, tools)
        }
    }
}

/**
 * A turn's searches, one group per place searched; the same page found twice
 * counts once, in the order each place was first searched (`TurnSources`).
 */
object TurnSources {
    data class Group(val origin: String, val payloads: List<JsonElement>) {
        val isWeb: Boolean get() = origin == "web"
        /** Every result of every search of this place, each kept the first time. */
        val results: List<JsonElement> get() {
            val seen = HashSet<String>()
            return payloads.flatMap { it.array("results") }.filter { seen.add(key(it, isWeb)) }
        }
        /** The server caps the results it sends but reports the true count. */
        val total: Int get() = results.size + payloads.sumOf { maxOf(0, (it.int("count") ?: 0) - it.array("results").size) }
        val queries: List<String> get() = payloads.map { it.str("query") }.filter { it.isNotEmpty() }.distinct()
    }

    fun byOrigin(blocks: List<DataBlock>): List<Group> {
        val groups = LinkedHashMap<String, MutableList<JsonElement>>()
        for (block in blocks) if (block.cardKind == "sources") groups.getOrPut(block.data.str("origin")) { mutableListOf() } += block.data
        return groups.map { (origin, payloads) -> Group(origin, payloads) }
    }

    /** A web result is its URL without the differences that are not ones; anything else, its title and subtitle. */
    fun key(item: JsonElement, isWeb: Boolean): String {
        if (isWeb) {
            val url = item.str("url").lowercase()
            if (url.isNotEmpty()) return url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trim('/')
        }
        return item.str("title") + "|" + item.str("subtitle")
    }

    /** The first few sources of the turn, every place together, for the thumbnails that lead the line. */
    fun leading(blocks: List<DataBlock>, max: Int = 3): List<Pair<JsonElement, Boolean>> =
        byOrigin(blocks).flatMap { g -> g.results.map { it to g.isWeb } }.take(max)
}

/** The provider's identifier, spelled so the model can act on it (`CandidatePickerCard.idToken`). */
fun candidateIdToken(collection: String, row: JsonElement): String {
    val id = row.str("id")
    if (id.isEmpty()) return ""
    return when (collection) {
        "movies", "series" -> "tmdb $id"
        "books" -> "google books $id"
        "podcasts" -> "podcast index $id"
        "games" -> "igdb $id"
        "music" -> "musicbrainz $id"
        "people" -> "wikidata $id"
        else -> id
    }
}

/**
 * Text cut in the middle of a reply, made presentable: an emphasis or code
 * span opened on the last line and not closed yet is held back until its
 * closing marker arrives, rather than showing as a raw `*` or `` ` ``.
 */
fun tidyPartialMarkdown(text: String): String {
    val lineStart = text.lastIndexOf('\n') + 1
    val line = text.substring(lineStart)
    var cut = line.length
    for (marker in listOf("**", "*", "`", "_")) {
        val positions = Regex(Regex.escape(marker)).findAll(line.substring(0, cut)).map { it.range.first }.toList()
        // `**` was counted as two `*`: look at single markers outside them.
        val singles = if (marker == "*") positions.filter { p -> !(line.startsWith("**", p) || (p > 0 && line.startsWith("**", p - 1))) } else positions
        if (marker == "_" ) {
            // An underscore inside a word (snake_case) is not emphasis.
            val emph = singles.filter { p -> (p == 0 || !line[p - 1].isLetterOrDigit()) || (p + 1 >= line.length || !line[p + 1].isLetterOrDigit()) }
            if (emph.size % 2 == 1) cut = minOf(cut, emph.last())
        } else if (singles.size % 2 == 1) cut = minOf(cut, singles.last())
    }
    return if (cut == line.length) text else text.substring(0, lineStart + cut).trimEnd()
}
