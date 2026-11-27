package eu.chezmaurice.parity

import eu.chezmaurice.core.chat.Piece
import eu.chezmaurice.core.chat.Snippet
import eu.chezmaurice.parity.Parity.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test

/** Long pastes and shared articles as cards (parity/vectors/maurice/chat-snippets.json). */
class ChatSnippetsParity {
    private fun article(o: JsonElement?): Snippet.Article {
        val a = o as? JsonObject ?: JsonObject(emptyMap())
        return Snippet.Article(
            title = a["title"].str() ?: "", site = a["site"].str() ?: "", author = a["author"].str() ?: "",
            date = a["date"].str() ?: "", url = a["url"].str() ?: "", text = a["text"].str() ?: "",
        )
    }

    private fun card(s: Snippet): Map<String, Any?> = when (s) {
        is Snippet.Paste -> mapOf("kind" to "paste", "text" to s.text, "lines" to s.lines)
        is Snippet.Article -> mapOf(
            "kind" to "article", "title" to s.title, "site" to s.site, "author" to s.author, "date" to s.date,
            "url" to s.url, "text" to s.text, "source" to s.source, "wordCount" to s.wordCount,
        )
    }

    @Test fun chatSnippets() = Parity.check("maurice", "chat-snippets") { c ->
        when (c.string("op")) {
            "isLong" -> c.string("text").orEmpty().let { mapOf("isLong" to Snippet.isLong(it), "lineCount" to Snippet.lineCount(it)) }
            "fence" -> mapOf("fenced" to Snippet.fence(c.string("text").orEmpty(), c.string("info").orEmpty()))
            "compose" -> {
                val snippets = c.array("snippets").orEmpty().mapNotNull { s ->
                    val o = s as? JsonObject ?: return@mapNotNull null
                    o["paste"].str()?.let { Snippet.Paste(it) } ?: o["article"]?.let { article(it) }
                }
                mapOf("sent" to Snippet.compose(c.string("typed").orEmpty(), snippets))
            }
            "insertion" -> Snippet.insertion(c.string("old").orEmpty(), c.string("new").orEmpty())
                ?.let { (inserted, remaining) -> mapOf("inserted" to inserted, "remaining" to remaining) }
            "article" -> article(c["article"]).let { mapOf("fenced" to it.fenced, "source" to it.source, "wordCount" to it.wordCount) }
            "read" -> {
                // ChatScreen: any message, a member's or Maurice's, is drawn as Snippet.pieces.
                val pieces = Snippet.pieces(c.string("content").orEmpty())
                mapOf(
                    "cards" to pieces.filterIsInstance<Piece.Card>().map { card(it.snippet) },
                    "hasText" to pieces.any { it is Piece.Text && it.text.isNotBlank() },
                )
            }
            else -> mapOf("unknown op" to c.string("op"))
        }
    }
}
