package eu.chezmaurice.core.carnet

import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.net.MauriceJson
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * The Calibre surface, `/api/v1/calibre/books/…` (`CalibreAPI.swift`):
 * chapters and their texts, the member's place, bookmarks, highlights, the
 * extract and summarize jobs; and the companion that follows a book.
 */
class CalibreApi(private val api: ApiClient) {
    private val base = "/api/v1/calibre/books"

    /**
     * Bare arrays, or wrapped — under [key], else under whatever key the
     * server used (`decodeList`). Anything else (an HTML error page, a
     * number) is a failure, not an empty list.
     */
    private inline fun <reified T> list(text: String, key: String): List<T> {
        val el = runCatching { MauriceJson.parseToJsonElement(text) }.getOrNull()
        val arr = el as? JsonArray
            ?: (el as? JsonObject)?.let { o -> o[key] as? JsonArray ?: o.values.firstOrNull { it is JsonArray } as? JsonArray ?: if (o.isEmpty()) JsonArray(emptyList()) else null }
            ?: throw kotlinx.serialization.SerializationException("Expected a list of $key")
        return arr.mapNotNull { runCatching { MauriceJson.decodeFromJsonElement<T>(it) }.getOrNull() }
    }
    private suspend fun text(method: HttpMethod, path: String, body: JsonElement? = null, timeout: Long = 15_000) =
        api.send(method, path, body, timeoutMillis = timeout).bodyAsText()

    /** The household's library, every book. */
    suspend fun books(): List<Book> = list(text(HttpMethod.Get, base, timeout = 30_000), "books")

    /**
     * An EPUB or a PDF for the household's library, as a form's `file`. A
     * book of tens of megabytes, then `calibredb add` on the server: two
     * minutes. A 503 is [LibraryUnavailable] (worth sending again); any
     * other refusal an [ApiError].
     */
    suspend fun addBook(bytes: ByteArray, filename: String): LibraryAddResult {
        val mime = if (filename.lowercase().endsWith(".epub")) "application/epub+zip" else "application/pdf"
        val response = try {
            api.send(HttpMethod.Post, "/api/v1/calibre/add", timeoutMillis = 120_000) {
                setBody(MultiPartFormDataContent(formData {
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentType, mime)
                        append(HttpHeaders.ContentDisposition, "filename=\"${filename.replace("\"", "")}\"")
                    })
                }))
            }
        } catch (e: ApiError) {
            // An empty body comes back as the status's reason: say "HTTP <status>", as iOS does.
            val blank = !e.fromServer && (e.message.isEmpty() || e.message == io.ktor.http.HttpStatusCode.fromValue(e.status).description)
            val message = if (blank) "HTTP ${e.status}" else e.message
            if (e.status == 503) throw LibraryUnavailable(message)
            throw ApiError(e.status, message, fromServer = e.fromServer)
        }
        return MauriceJson.decodeFromString(response.bodyAsText())
    }

    suspend fun chapters(bookId: Int): List<Chapter> = list(text(HttpMethod.Get, "$base/$bookId/chapters", timeout = 30_000), "chapters")

    suspend fun chapter(bookId: Int, slug: String): ChapterText =
        MauriceJson.decodeFromString(text(HttpMethod.Get, "$base/$bookId/chapters/by-slug/${slug.encodeURLPathPart()}", timeout = 30_000))

    suspend fun chapterSummary(bookId: Int, slug: String): ChapterSummary =
        MauriceJson.decodeFromString(text(HttpMethod.Get, "$base/$bookId/chapters/by-slug/${slug.encodeURLPathPart()}/summary", timeout = 30_000))

    /** Null when the book was never opened. */
    suspend fun progress(bookId: Int): ReadingProgress? {
        val t = text(HttpMethod.Get, "$base/$bookId/reading-progress")
        return runCatching { MauriceJson.decodeFromString<ReadingProgress>(t) }.getOrNull()
    }

    suspend fun setProgress(bookId: Int, chapterIndex: Int, chapterSlug: String, view: String, position: Double) {
        text(HttpMethod.Post, "$base/$bookId/reading-progress", buildJsonObject {
            put("chapter_index", chapterIndex); put("chapter_slug", chapterSlug); put("view", view); put("position", position)
        })
    }

    /** Paused, the marker is held: an index or a chapter further on can be read without counting. */
    suspend fun setTracking(bookId: Int, paused: Boolean) {
        text(HttpMethod.Post, "$base/$bookId/reading-progress/tracking", buildJsonObject { put("paused", paused) })
    }

    suspend fun resetProgress(bookId: Int) { text(HttpMethod.Delete, "$base/$bookId/reading-progress") }

    suspend fun bookmarks(bookId: Int): List<Bookmark> = list(text(HttpMethod.Get, "$base/$bookId/bookmarks"), "bookmarks")

    suspend fun toggleBookmark(bookId: Int, chapterSlug: String, view: String): BookmarkToggle =
        MauriceJson.decodeFromString(text(HttpMethod.Post, "$base/$bookId/bookmarks", buildJsonObject { put("chapter_slug", chapterSlug); put("view", view) }))

    suspend fun setBookmarkNote(bookId: Int, bookmarkId: Int, note: String?) {
        text(HttpMethod.Put, "$base/$bookId/bookmarks/$bookmarkId", buildJsonObject { put("note", note?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull) })
    }

    suspend fun highlights(bookId: Int): List<Highlight> = list(text(HttpMethod.Get, "$base/$bookId/highlights"), "highlights")

    /** [view] is what makes the offsets mean anything: the same numbers land elsewhere in the summary. */
    suspend fun createHighlight(bookId: Int, chapterSlug: String, quote: String, color: String, note: String?, start: Int, end: Int, view: String): Highlight =
        MauriceJson.decodeFromString(text(HttpMethod.Post, "$base/$bookId/highlights", highlightBody(quote, color, note, start, end, view) { put("chapter_slug", chapterSlug) }))

    suspend fun updateHighlight(bookId: Int, id: Int, note: String?, color: String): Highlight =
        MauriceJson.decodeFromString(text(HttpMethod.Put, "$base/$bookId/highlights/$id", highlightPatch(note, color)))

    suspend fun deleteHighlight(bookId: Int, id: Int) { text(HttpMethod.Delete, "$base/$bookId/highlights/$id") }

    suspend fun status(bookId: Int): SummaryStatus = MauriceJson.decodeFromString(text(HttpMethod.Get, "$base/$bookId/status"))

    /** Starts summarizing the chapters without a summary; poll [status]. */
    suspend fun summarize(bookId: Int) { text(HttpMethod.Post, "$base/$bookId/summarize", buildJsonObject {}) }

    /** Splits the EPUB into chapters; poll [chapters] and [status]. */
    suspend fun extract(bookId: Int) { text(HttpMethod.Post, "$base/$bookId/extract", buildJsonObject {}) }

    /**
     * Follow a book with Maurice: a row of kind `companion` bound to the book
     * at the reading position. `kind` is explicit: without it, a domain.
     */
    suspend fun follow(bookId: Int, name: String) {
        text(HttpMethod.Post, "/api/maurices", buildJsonObject {
            put("name", name); put("kind", "companion"); put("tagline", "")
            put("context", buildJsonArray {
                add(buildJsonObject {
                    put("type", "book"); put("id", bookId); put("representation", "summary")
                    put("scope", buildJsonObject { put("mode", "progress") })
                })
            })
        })
    }

    // Articles: the same reading surface, under the garden.

    private fun articlePath(slug: String, locale: String, suffix: String = "", owner: String? = null) =
        "/api/v1/garden/articles/${slug.encodeURLPathPart()}$suffix?locale=${locale.encodeURLParameter()}" +
            (owner?.let { "&owner=${it.encodeURLParameter()}" } ?: "")

    suspend fun article(slug: String, locale: String): Article = MauriceJson.decodeFromString(text(HttpMethod.Get, articlePath(slug, locale)))

    /**
     * 404: the page was never captured. [owner]: an article given from
     * another household and shared live with the member, read in its owner's notes.
     */
    suspend fun articleText(slug: String, locale: String, owner: String? = null): ArticleText =
        MauriceJson.decodeFromString(text(HttpMethod.Get, articlePath(slug, locale, "/text", owner), timeout = 30_000))

    suspend fun articleHighlights(slug: String, locale: String): List<Highlight> =
        list<Highlight>(text(HttpMethod.Get, articlePath(slug, locale, "/highlights")), "highlights").map { it.copy(chapterSlug = it.slug ?: slug) }

    suspend fun createArticleHighlight(slug: String, locale: String, quote: String, color: String, note: String?, start: Int, end: Int, view: String): Highlight =
        MauriceJson.decodeFromString<Highlight>(text(HttpMethod.Post, "/api/v1/garden/articles/${slug.encodeURLPathPart()}/highlights",
            highlightBody(quote, color, note, start, end, view) { put("locale", locale) })).let { it.copy(chapterSlug = it.slug ?: slug) }

    suspend fun updateArticleHighlight(slug: String, id: Int, note: String?, color: String): Highlight =
        MauriceJson.decodeFromString<Highlight>(text(HttpMethod.Put, "/api/v1/garden/articles/${slug.encodeURLPathPart()}/highlights/$id", highlightPatch(note, color)))
            .let { it.copy(chapterSlug = it.slug ?: slug) }

    suspend fun deleteArticleHighlight(slug: String, id: Int) {
        text(HttpMethod.Delete, "/api/v1/garden/articles/${slug.encodeURLPathPart()}/highlights/$id")
    }

    private fun highlightBody(quote: String, color: String, note: String?, start: Int, end: Int, view: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("quote", quote); put("color", color); put("view", view)
        if (!note.isNullOrEmpty()) put("note", note)
        put("start_offset", start); put("end_offset", end)
        extra()
    }
    private fun highlightPatch(note: String?, color: String) = buildJsonObject {
        put("note", note?.takeIf { it.isNotEmpty() }?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
        put("color", color)
    }

    companion object {
        fun error(e: Throwable): String = (e as? ApiError)?.message ?: e.message ?: "Something went wrong."
    }
}
