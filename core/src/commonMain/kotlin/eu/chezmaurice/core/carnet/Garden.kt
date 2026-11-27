package eu.chezmaurice.core.carnet

import eu.chezmaurice.core.net.MauriceJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// What Carnet shows: the member's entries, one shelf of every kind
// (`Models/Shelf.swift`). Decoded leniently: an entry or a kind this build
// does not know is skipped, never fatal.

enum class ShelfKind(val wire: String, val label: String, val singular: String) {
    Books("books", "Books", "Book"),
    Articles("articles", "Articles", "Article"),
    Movies("movies", "Films", "Film"),
    Series("series", "Series", "Series"),
    Music("music", "Music", "Album"),
    Podcasts("podcasts", "Podcasts", "Podcast"),
    Games("games", "Games", "Game"),
    Posts("posts", "Posts", "Post");

    val squareCover: Boolean get() = this == Music || this == Podcasts

    companion object {
        fun of(wire: String?): ShelfKind? = entries.firstOrNull { it.wire == wire }
    }
}

@Serializable
data class MemberBrief(
    /** Empty for a member no longer in the household (`shared_by` then gives the username alone). */
    val id: String = "",
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_color") val avatarColor: String = "#6b6460",
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class ShelfSource(
    /** book | article. */
    val type: String = "",
    @SerialName("book_id") val bookId: Int? = null,
    val chapters: Int? = null,
    val progress: Progress? = null,
    val url: String? = null,
    val captured: Boolean? = null,
    @SerialName("word_count") val wordCount: Int? = null,
    @SerialName("reading_minutes") val readingMinutes: Int? = null,
    val summary: Summary? = null,
    /** An article another member gave a copy of (`shared_by`): who. */
    @SerialName("shared_by") val sharedBy: MemberBrief? = null,
) {
    @Serializable
    data class Progress(
        @SerialName("chapter_slug") val chapterSlug: String = "",
        @SerialName("chapter_title") val chapterTitle: String? = null,
        val fraction: Double = 0.0,
        val finished: Boolean = false,
        val tracking: Boolean = true,
        @SerialName("updated_at") val updatedAt: String = "",
    )

    @Serializable
    data class Summary(val text: String = "", val long: String? = null, val current: Boolean = false)

    val isBook: Boolean get() = type == "book"
    val isReadable: Boolean get() = isBook || captured == true
}

/** draft | published. */
@Serializable
data class SharedFace(val state: String = "draft") {
    val published: Boolean get() = state == "published"
}

@Serializable
data class ShelfEntry(
    /** `<collection>/<locale>/<slug>`, or `calibre/<bookId>` for a book only in the library. */
    val id: String,
    @SerialName("kind") val kindWire: String,
    val collection: String? = null,
    val locale: String? = null,
    val slug: String? = null,
    val title: String = "",
    val byline: String? = null,
    val date: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val image: String? = null,
    val tags: List<String> = emptyList(),
    val rating: Int? = null,
    val status: String? = null,
    val source: ShelfSource? = null,
    val mine: Mine? = null,
    val shared: SharedFace? = null,
    val archived: Boolean? = null,
    val translations: List<Translation> = emptyList(),
    /** Set when this is another member's entry, shared with me. */
    val owner: MemberBrief? = null,
    val participants: List<MemberBrief>? = null,
) {
    @Serializable data class Mine(val notes: Int = 0, val opened: Boolean = false)
    @Serializable data class Translation(val id: String, val locale: String = "", val shared: SharedFace? = null)

    val kind: ShelfKind get() = ShelfKind.of(kindWire) ?: ShelfKind.Posts
    val isLibraryOnly: Boolean get() = collection == null
    /** The title's first character as the eye reads it (an emoji whole, an accent with its letter), in capitals. */
    val coverLetter: String get() = firstGrapheme(title.trim()).uppercase()
    val kindLine: String get() = listOfNotNull(kind.singular, byline).joinToString(" · ")
    val isUntouched: Boolean get() = shared == null && (mine == null || (!mine.opened && mine.notes == 0))
    val noteCount: Int get() = mine?.notes ?: 0
    val isForeign: Boolean get() = owner != null
    val people: List<MemberBrief> get() = listOfNotNull(owner) + participants.orEmpty()
    val isShared: Boolean get() = people.isNotEmpty()
    val publishedSomewhere: Boolean get() = shared?.published == true || translations.any { it.shared?.published == true }
}

@Serializable
data class KindCount(@SerialName("kind") val kindWire: String, val count: Int = 0) {
    val kind: ShelfKind? get() = ShelfKind.of(kindWire)
}

@Serializable
data class GardenInfo(val username: String = "", val site: String? = null)

/** `GET /api/v1/garden/entries?view=shelf`. */
@Serializable
data class Shelf(
    val garden: GardenInfo = GardenInfo(),
    val kinds: List<KindCount> = emptyList(),
    val entries: List<ShelfEntry> = emptyList(),
) {
    companion object {
        /** Kinds and entries this build does not know are dropped, one by one. */
        fun parse(text: String): Shelf {
            val o = MauriceJson.parseToJsonElement(text) as JsonObject
            return Shelf(
                garden = o["garden"]?.let { MauriceJson.decodeFromJsonElement(GardenInfo.serializer(), it) } ?: GardenInfo(),
                kinds = lossy(o["kinds"], KindCount.serializer()).filter { it.kind != null },
                entries = lossy(o["entries"], ShelfEntry.serializer()).filter { ShelfKind.of(it.kindWire) != null },
            )
        }
    }
}

/** Something the member (or a co-reader) wrote on an entry: a note, a quote, a highlight… */
@Serializable
data class MineItem(
    /** note | quote | resonance | highlight | fragment. */
    val kind: String = "note",
    @SerialName("id") val serverId: String? = null,
    val date: String? = null,
    val text: String? = null,
    val quote: String? = null,
    val summary: String? = null,
    @SerialName("where") val place: Place? = null,
    val from: Origin? = null,
    val author: MemberBrief? = null,
    val conversation: Exchange? = null,
    /**
     * Whether the member may rewrite it, or take it off (an owner may take
     * off what someone else signed on their shared entry, not rewrite it).
     * Absent from a server that cannot edit notes: nothing is offered then.
     */
    val editable: Boolean? = null,
    val deletable: Boolean? = null,
    /** The day it was last rewritten (`YYYY-MM-DD`), or null. */
    val edited: String? = null,
) {
    val canEdit: Boolean get() = editable == true && serverId != null
    val canDelete: Boolean get() = deletable == true && serverId != null

    @Serializable data class Place(
        val page: String? = null, @SerialName("chapter_slug") val chapterSlug: String? = null,
        @SerialName("chapter_title") val chapterTitle: String? = null, val view: String? = null,
    )
    @Serializable data class Origin(val label: String = "", @SerialName("entry_id") val entryId: String? = null, @SerialName("published_url") val publishedUrl: String? = null)
    @Serializable data class Exchange(val id: String, val initiator: MemberBrief? = null)

    val id: String get() = serverId ?: "$kind|${date.orEmpty()}|${author?.id.orEmpty()}|${quote.orEmpty()}|${text.orEmpty()}"
}

@Serializable
data class EntryDetail(val mine: Mine? = null, val shared: Shared? = null) {
    @Serializable data class Mine(
        val notes: Int = 0, val opened: Boolean = false, @SerialName("web_path") val webPath: String? = null,
        val file: String = "", val prose: String = "", val items: List<MineItem> = emptyList(),
    )
    @Serializable data class Link(val basename: String = "", val label: String = "", @SerialName("entry_id") val entryId: String? = null, @SerialName("published_url") val publishedUrl: String? = null)
    @Serializable data class Shared(
        val state: String = "draft", @SerialName("web_path") val webPath: String? = null, val file: String = "",
        @SerialName("public_url") val publicUrl: String? = null, val title: String? = null, val body: String = "",
        val links: List<Link> = emptyList(),
    ) {
        val published: Boolean get() = state == "published"
    }
}

/** idle | queued | running | failed. */
@Serializable
data class DeployState(val status: String = "idle", val error: String? = null) {
    val inProgress: Boolean get() = status == "running" || status == "queued"
    val failed: Boolean get() = status == "failed"
}

/** An entry's page, as every entry route answers it: the row, what is written on it, the site's deploy. */
data class EntryPage(val entry: ShelfEntry, val detail: EntryDetail, val deploy: DeployState?) {
    companion object {
        fun parse(text: String): EntryPage {
            val o = MauriceJson.parseToJsonElement(text) as JsonObject
            return EntryPage(
                MauriceJson.decodeFromJsonElement(ShelfEntry.serializer(), o),
                MauriceJson.decodeFromJsonElement(EntryDetail.serializer(), o),
                o["deploy"]?.let { runCatching { MauriceJson.decodeFromJsonElement(DeployState.serializer(), it) }.getOrNull() },
            )
        }
    }
}

/** One note of the notes feed: which entry, and the note. */
data class FeedNote(val entryId: String, val item: MineItem) {
    val id: String get() = "$entryId|${item.id}"
}

/** `GET …/entries?view=notes`. */
data class NotesFeed(val entries: List<ShelfEntry>, val notes: List<FeedNote>) {
    companion object {
        fun parse(text: String): NotesFeed {
            val o = MauriceJson.parseToJsonElement(text) as JsonObject
            val notes = (o["notes"] as? JsonArray).orEmpty().mapNotNull { n ->
                val obj = n as? JsonObject ?: return@mapNotNull null
                val entryId = (obj["entry_id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                runCatching { FeedNote(entryId, MauriceJson.decodeFromJsonElement(MineItem.serializer(), obj)) }.getOrNull()
            }
            return NotesFeed(lossy(o["entries"], ShelfEntry.serializer()), notes)
        }
    }
}

/** A conversation held about an entry, and its summary. */
@Serializable
data class EntryConversation(
    val id: String,
    val title: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("message_count") val messageCount: Int = 0,
    /** entry | context. */
    val via: String = "",
    val maurice: Persona? = null,
    val summary: Summary? = null,
) {
    @Serializable data class Persona(val id: String, val name: String = "")
    @Serializable data class Summary(val id: String = "", val text: String = "", @SerialName("written_at") val writtenAt: String? = null, val stale: Boolean = false)

    val displayTitle: String get() = title?.trim()?.ifEmpty { null } ?: "Untitled conversation"
}

internal fun <T> lossy(array: JsonElement?, serializer: kotlinx.serialization.KSerializer<T>): List<T> =
    (array as? JsonArray).orEmpty().mapNotNull { runCatching { MauriceJson.decodeFromJsonElement(serializer, it) }.getOrNull() }

/** A garden image's site path (`/images/<member>/resources/…`) as the open route that serves it. */
fun gardenImageUrl(baseUrl: String, image: String?): String? {
    val path = image?.takeIf { it.startsWith("/images/") } ?: return null
    // Swift's split skips empty pieces: "/images//resources/x" has no member.
    val parts = path.removePrefix("/images/").trimStart('/').split("/", limit = 2)
    if (parts.size != 2 || !parts[1].startsWith("resources/")) return null
    return encodeInvalidUrlCharacters(baseUrl.trimEnd('/') + "/api/garden-images/" + parts[0] + "/" + parts[1].removePrefix("resources/"))
}

/** What `URL(string:)` does to a string with spaces or accents: percent-encode them (UTF-8), keep the rest. */
private fun encodeInvalidUrlCharacters(url: String): String {
    val allowed = "-._~!$&'()*+,;=:@/?#[]"
    fun hex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    val out = StringBuilder()
    var i = 0
    while (i < url.length) {
        val c = url[i]
        val keep = (c < '\u0080' && (c.isLetterOrDigit() || c in allowed)) ||
            (c == '%' && i + 2 < url.length && hex(url[i + 1]) && hex(url[i + 2]))
        val n = if (c.isHighSurrogate() && i + 1 < url.length && url[i + 1].isLowSurrogate()) 2 else 1
        if (keep) out.append(c)
        else url.substring(i, i + n).encodeToByteArray().forEach { out.append('%').append(((it.toInt() and 0xff) + 0x100).toString(16).substring(1).uppercase()) }
        i += n
    }
    return out.toString()
}
