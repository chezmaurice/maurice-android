package eu.chezmaurice.core.carnet

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.max
import kotlin.math.roundToInt

// The reading surface of the server (`Reading.swift`): the Calibre library's
// books and chapters, the member's place in a book, chapter bookmarks,
// passage highlights; an article's captured text. Books and chapters come
// in camelCase, the member's rows in snake_case, as the server sends them.

/** One chapter of a book's table of contents (`/books/:id/chapters`). */
@Serializable
data class Chapter(
    val index: Int = 0,
    val slug: String,
    val title: String = "",
    val wordCount: Int? = null,
    val summaryWordCount: Int? = null,
    val hasSummary: Boolean = false,
) {
    /** About 230 words a minute, the summary's length when there is one. */
    val readingMinutes: Int get() = max(1, ((summaryWordCount ?: wordCount ?: 0) / 230.0).roundToInt())
}

/** A book of the household's library (`/api/v1/calibre/books`). */
@Serializable
data class Book(
    val id: Int,
    val title: String = "",
    val authors: List<String> = emptyList(),
) {
    val author: String get() = if (authors.isEmpty()) "Unknown" else authors.joinToString(", ")
    /** First letter for the placeholder cover. */
    val coverLetter: String get() = title.trim().take(1).uppercase()
}

/**
 * What the library said of a file sent to it (`POST /api/v1/calibre/add`).
 * Calibre adds nothing when it already holds the book: a home library says
 * so in its output, a hosted one answers [duplicate] with the book it holds.
 */
@Serializable
data class LibraryAddResult(
    val ok: Boolean = false,
    val bookId: Int? = null,
    val filename: String? = null,
    val output: String? = null,
    val duplicate: Boolean? = null,
) {
    val alreadyThere: Boolean get() = duplicate == true || (bookId == null && (output ?: "").contains("already exist", ignoreCase = true))
}

/** The household's library did not answer (503): the book is worth sending again later, unlike a refusal. */
class LibraryUnavailable(message: String) : Exception(message)

/** A file the household's library takes: an EPUB or a PDF. */
fun isLibraryBook(filename: String): Boolean = filename.lowercase().let { it.endsWith(".epub") || it.endsWith(".pdf") }

@Serializable data class ChapterText(val title: String = "", val text: String = "", val wordCount: Int? = null)
@Serializable data class ChapterSummary(val exists: Boolean = false, val title: String? = null, val summary: String? = null)

/** The member's place in a book; the server keeps whatever was written last, from any device. */
@Serializable
data class ReadingProgress(
    @SerialName("book_id") val bookId: Int? = null,
    @SerialName("chapter_index") val chapterIndex: Int? = null,
    @SerialName("chapter_slug") val chapterSlug: String? = null,
    /** full | summary. */
    val view: String? = null,
    /** 0 while tracking is paused. */
    val enabled: Int? = null,
    /** The active view's position: a character offset in the text, a paragraph in the summary. */
    val position: Double? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    val paused: Boolean get() = enabled == 0
}

@Serializable
data class Bookmark(
    val id: Int,
    @SerialName("book_id") val bookId: Int? = null,
    @SerialName("chapter_slug") val chapterSlug: String = "",
    val view: String = "full",
    val note: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable data class BookmarkToggle(val bookmarked: Boolean = false, val bookmark: Bookmark? = null)

/**
 * A passage marked in a text. The offsets index the text in UTF-16 units —
 * a Kotlin string's own — and belong to one of a chapter's two texts, [view].
 * An article's highlight carries its slug in [chapterSlug].
 */
@Serializable
data class Highlight(
    val id: Int,
    @SerialName("book_id") val bookId: Int? = null,
    @SerialName("chapter_slug") val chapterSlug: String = "",
    val slug: String? = null,
    val quote: String = "",
    val note: String? = null,
    val color: String = "yellow",
    @SerialName("start_offset") val startOffset: Int? = null,
    @SerialName("end_offset") val endOffset: Int? = null,
    val view: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    /** The conversation with Maurice opened from this passage, and what it came to.
     *  Only the lists send them; a create or an update answers without. */
    @SerialName("conversation_id") val conversationId: String? = null,
    val conversation: HighlightConversation? = null,
) {
    /** Absent from a server that predates the column: the full text, which is all there was. */
    val readerView: String get() = view ?: "full"
    fun covers(offset: Int): Boolean = startOffset != null && endOffset != null && offset >= startOffset && offset < endOffset
}

/** A passage's conversation with Maurice: its title, and its summary — the one
 *  the entry's fiche carries — once the conversation has settled. */
@Serializable
data class HighlightConversation(val id: String, val title: String? = null, val summary: String? = null)

/** The highlight palette's keys: "yellow" is the ink wash, the default. */
val HIGHLIGHT_COLORS = listOf("yellow", "green", "pink", "blue")

@Serializable data class JobInfo(val action: String = "", val state: String = "", val error: String? = null, val at: String? = null)

@Serializable
data class SummaryStatus(val chapters: Int? = null, val summarized: Int? = null, val indexed: Int? = null, val job: JobInfo? = null) {
    val isComplete: Boolean get() = (chapters ?: 0) > 0 && (summarized ?: 0) >= (chapters ?: 0)
}

/** An article (`GET /garden/articles/:slug`), as far as the reader needs it. */
@Serializable
data class Article(
    val slug: String,
    val locale: String,
    val title: String = "",
    val url: String? = null,
    @SerialName("saved_at") val savedAt: String? = null,
    val author: String? = null,
    val publication: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val image: String? = null,
    val summary: String? = null,
    @SerialName("summary_long") val summaryLong: String? = null,
    @SerialName("word_count") val wordCount: Int? = null,
    @SerialName("has_text") val hasText: Boolean? = null,
    @SerialName("text_rev") val textRev: String? = null,
    /** A copy another member gave by sharing it: who. */
    @SerialName("shared_by") val sharedBy: MemberBrief? = null,
    /**
     * The username whose notes hold it, for an article given from another
     * household and shared live with the member (`@<owner>/articles/…` on the
     * shelf): its text is read with `?owner=`. Null for the member's own.
     * Not part of what the server sends.
     */
    @Transient val owner: String? = null,
) {
    /** Stable across locales (two may share a slug), and apart from the member's own article at the same slug. */
    val id: String get() = (owner?.let { "@$it/" } ?: "") + "$locale/$slug"
    /** What the Summary view reads: the long one, else the paragraph. */
    val readableSummary: String get() = summaryLong?.trim()?.takeIf { it.isNotEmpty() } ?: summary.orEmpty()
    val hasSummary: Boolean get() = readableSummary.isNotEmpty()
    val readingMinutes: Int get() = max(1, ((wordCount ?: 0) / 230.0).roundToInt())
    val bylineShort: String get() = listOfNotNull(publication, publishedAt ?: savedAt).joinToString(" · ")

    companion object {
        /**
         * An article read in its owner's notes — given from another household
         * and shared live with the member — made from its shelf row: the
         * article's own page (`GET …/articles/:slug`) is the owner's, not theirs.
         */
        fun shared(entry: ShelfEntry): Article = Article(
            slug = entry.slug.orEmpty(),
            locale = entry.locale.orEmpty(),
            title = entry.title,
            url = entry.source?.url,
            author = entry.byline,
            summary = entry.source?.summary?.text,
            summaryLong = entry.source?.summary?.long,
            hasText = entry.source?.captured ?: true,
            owner = entry.owner?.username,
        )
    }
}

/** A stretch the page set apart (heading, strong, em), in UTF-16 offsets like a highlight's. */
@Serializable data class TextStyle(val start: Int, val end: Int, val kind: String)

@Serializable data class ArticleText(val slug: String = "", val locale: String = "", val title: String? = null, val text: String = "", val styles: List<TextStyle>? = null)

/**
 * The summary's position is a paragraph index, on the server and on the
 * device; the reader speaks character offsets. Where each non-blank line
 * starts, in UTF-16 units.
 */
fun paragraphStarts(text: String): List<Int> {
    val starts = mutableListOf<Int>()
    var offset = 0
    for (line in text.split("\n")) {
        if (line.isNotBlank()) starts.add(offset)
        offset += line.length + 1
    }
    return starts
}

fun charOffsetOfParagraph(text: String, index: Int): Int {
    val starts = paragraphStarts(text)
    if (starts.isEmpty() || index <= 0) return 0
    return starts[minOf(index, starts.size - 1)]
}

fun paragraphOfChar(text: String, char: Int): Int = paragraphStarts(text).indexOfLast { it <= char }.coerceAtLeast(0)
