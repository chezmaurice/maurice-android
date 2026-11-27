package eu.chezmaurice.core.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A long piece of text the composer carries as a card above the field — `ChatSnippets.swift`. */
sealed interface Snippet {
    val fenced: String

    /** Text pasted or dropped into the field. */
    data class Paste(val text: String) : Snippet {
        override val fenced: String get() = fence(text, "pasted")
        val lines: Int get() = lineCount(text)
    }

    /**
     * An article shared from another app: a fenced `article` block whose
     * first lines say what it is (`title: …`, `url: …`) and whose body, after
     * a blank line, is its text. Empty text: the page could not be read.
     */
    data class Article(
        val title: String,
        val site: String = "",
        val author: String = "",
        /** YYYY-MM-DD, when the page says. */
        val date: String = "",
        val url: String,
        val text: String = "",
    ) : Snippet {
        override val fenced: String get() {
            val header = listOf("title" to title, "site" to site, "author" to author, "date" to date, "url" to url)
                .mapNotNull { (k, v) -> v.replace('\n', ' ').trim().takeIf { it.isNotEmpty() }?.let { "$k: $it" } }
                .joinToString("\n")
            val body = text.trim()
            return fence(listOf(header).plus(if (body.isEmpty()) emptyList() else listOf(body)).joinToString("\n\n"), "article")
        }
        val wordCount: Int get() = text.split(Regex("\\s+")).count { it.isNotBlank() }
        /** The publication, or the address's host ("" when the address has none, as `URL(string:)?.host`). */
        val source: String get() = site.ifEmpty { HOST.find(url)?.groupValues?.get(1)?.removePrefix("www.").orEmpty() }
    }

    companion object {
        /** A paste this long leaves the field for a card. */
        const val MIN_CHARACTERS = 1_000
        const val MIN_LINES = 20

        /** Characters as the member sees them (an emoji, an accented letter: one each), as Swift's `count`. */
        fun isLong(text: String) = Graphemes.count(text) >= MIN_CHARACTERS || lineCount(text) >= MIN_LINES

        fun lineCount(text: String) = text.trim('\n', '\r').count { it == '\n' } + 1

        /** `text` fenced, the fence one backtick longer than any run inside (three at least). */
        fun fence(text: String, info: String): String {
            var longest = 0; var run = 0
            for (c in text) if (c == '`') { run++; longest = maxOf(longest, run) } else run = 0
            val f = "`".repeat(maxOf(3, longest + 1))
            return "$f$info\n${text.trim('\n', '\r')}\n$f"
        }

        /**
         * A message read back, in order: its text and the cards it carries —
         * a `pasted` or `article` fenced block anywhere in it, from a member
         * or from Maurice, as ReplyDocument reads them. Fences follow
         * CommonMark: the closing one at least as long as the opening, an
         * unclosed one runs to the end, and a card inside another code block
         * is that block's text.
         */
        fun pieces(content: String): List<Piece> {
            val pieces = mutableListOf<Piece>()
            val text = StringBuilder()
            fun flush() {
                if (text.isNotBlank()) pieces += Piece.Text(text.toString().trim('\n'))
                text.clear()
            }
            val lines = content.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            var i = 0
            while (i < lines.size) {
                val open = OPENING.matchEntire(lines[i])
                if (open == null) { text.append(lines[i]).append('\n'); i++; continue }
                val fence = open.groupValues[1]
                val info = open.groupValues[2].trim().split(' ', '\t').first()
                var j = i + 1
                while (j < lines.size && !closes(lines[j], fence)) j++
                if (info == "pasted" || info == "article") {
                    flush()
                    val body = lines.subList(i + 1, minOf(j, lines.size)).joinToString("\n")
                    pieces += Piece.Card(if (info == "pasted") Paste(body) else articleFrom(body))
                } else {
                    // Another code block: text, fences and all.
                    for (k in i..minOf(j, lines.size - 1)) text.append(lines[k]).append('\n')
                }
                i = j + 1
            }
            flush()
            return pieces
        }

        /** The cards of a message, and its text without them. */
        fun parse(content: String): Pair<List<Snippet>, String> {
            val all = pieces(content)
            return all.filterIsInstance<Piece.Card>().map { it.snippet } to
                all.filterIsInstance<Piece.Text>().joinToString("\n\n") { it.text }
        }

        private val OPENING = Regex("^ {0,3}(`{3,}|~{3,})([^`]*)$")
        private val HOST = Regex("^[A-Za-z][A-Za-z0-9+.-]*://(?:[^/?#@\\s]*@)?([^/?#:\\s\\[\\]]+)")

        private fun closes(line: String, fence: String): Boolean {
            val t = line.trimEnd(' ', '\t')
            val run = t.trimStart(' ')
            if (t.length - run.length > 3) return false
            return run.length >= fence.length && run.all { it == fence[0] }
        }

        private fun articleFrom(body: String): Article {
            val trimmed = body.trim('\n')
            val parts = trimmed.split("\n\n", limit = 2)
            val fields = parts[0].lines().mapNotNull { line ->
                val i = line.indexOf(':')
                if (i < 0) null else line.substring(0, i) to line.substring(i + 1).trim()
            }.filter { it.first in setOf("title", "site", "author", "date", "url") }.toMap()
            // A block without the header is all text.
            if (fields.isEmpty()) return Article(title = "", url = "", text = trimmed)
            return Article(
                title = fields["title"].orEmpty(), site = fields["site"].orEmpty(), author = fields["author"].orEmpty(),
                date = fields["date"].orEmpty(), url = fields["url"].orEmpty(), text = parts.getOrElse(1) { "" },
            )
        }

        /** What is sent: the cards first, as images lead, then what was typed. */
        fun compose(typed: String, snippets: List<Snippet>): String =
            (snippets.map { it.fenced } + listOfNotNull(typed.takeIf { it.isNotEmpty() })).joinToString("\n\n")

        /**
         * The long run an edit inserted in one go — a paste — and the field
         * without it; null for typing and short pastes. Compared character by
         * character as the member sees them, never inside an emoji.
         */
        fun insertion(old: String, new: String): Pair<String, String>? {
            val o = Graphemes.split(old)
            val n = Graphemes.split(new)
            if (n.size <= o.size) return null
            var prefix = 0
            while (prefix < o.size && o[prefix] == n[prefix]) prefix++
            var suffix = 0
            while (suffix < o.size - prefix && o[o.size - 1 - suffix] == n[n.size - 1 - suffix]) suffix++
            val inserted = n.subList(prefix, n.size - suffix).joinToString("")
            if (!isLong(inserted)) return null
            return inserted to (n.subList(0, prefix) + n.subList(n.size - suffix, n.size)).joinToString("")
        }
    }
}

/** A piece of a message read back: text, or a card. */
sealed interface Piece {
    data class Text(val text: String) : Piece
    data class Card(val snippet: Snippet) : Piece
}

/** `POST /api/v1/articles/read`. */
@Serializable
data class ReadArticle(
    val url: String = "",
    val title: String? = null,
    @SerialName("site_name") val siteName: String? = null,
    val author: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val text: String? = null,
)
