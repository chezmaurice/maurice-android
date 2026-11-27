package eu.chezmaurice.core.carnet

import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.net.MauriceJson
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.http.content.ByteArrayContent
import io.ktor.client.request.setBody
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Why a note could not be rewritten or taken off. */
class NoteEditError(val kind: Kind) : Exception(kind.name) {
    enum class Kind {
        /** 404 (`note_not_found` or `changed`): the note is not what the member saw any more. */
        Changed,
        /** 403: not theirs to edit. */
        Forbidden,
    }
}

@Serializable private data class ArticleSummaries(val summary: String = "", @kotlinx.serialization.SerialName("summary_long") val summaryLong: String? = null)
@Serializable private data class Conversations(val conversations: List<EntryConversation> = emptyList())
@Serializable private data class ConversationSummary(val summary: EntryConversation.Summary)
@Serializable private data class Filed(val domain: DomainChoice? = null)

/** One of the member's domains, as an article is filed under it. */
@Serializable data class DomainChoice(val id: String, val name: String, val icon: String? = null)

/**
 * What a save answers that the share sheet uses: the article, and the domain
 * the household filed it under with the others to choose from (none from a
 * server before 8 November 2026, or for a member without domains).
 */
@Serializable data class SavedArticle(
    val slug: String = "",
    val locale: String? = null,
    val duplicate: Boolean = false,
    @kotlinx.serialization.SerialName("needs_capture") val needsCapture: Boolean = false,
    val domain: DomainChoice? = null,
    @kotlinx.serialization.SerialName("domain_choices") val domainChoices: List<DomainChoice> = emptyList(),
)

/**
 * The garden's routes, `/api/v1/garden/…` (`GardenAPI.swift`): the shelf,
 * one entry and what is written on it, notes, the shared face and its
 * publication, archiving, the conversations about an entry.
 */
class GardenApi(private val api: ApiClient) {
    private val base = "/api/v1/garden"

    val baseUrl: String get() = api.baseUrl

    /** The shelf; the raw text too, for the offline copy. */
    suspend fun shelf(archived: Boolean = false): Pair<Shelf, String> {
        val text = api.send(HttpMethod.Get, "$base/entries?view=shelf${if (archived) "&archived=1" else ""}", timeoutMillis = 30_000).bodyAsText()
        return Shelf.parse(text) to text
    }

    /**
     * A page kept from another app (`POST /garden/articles`): the server
     * fetches and reads it, and files it under one of the member's domains
     * or none. Answers where it went and the domains to choose from instead.
     */
    suspend fun saveArticle(url: String, locale: String, comment: String?, title: String?): SavedArticle {
        val text = api.send(HttpMethod.Post, "$base/articles", buildJsonObject {
            put("url", url); put("locale", locale); put("source_client", "android-share")
            if (!comment.isNullOrBlank()) put("comment", comment)
            if (!title.isNullOrBlank()) put("title", title)
        }, timeoutMillis = 60_000).bodyAsText()
        return runCatching { MauriceJson.decodeFromString(SavedArticle.serializer(), text) }.getOrElse { SavedArticle() }
    }

    /** File a saved article under one of the member's domains, or (null) under none, for good. */
    suspend fun setArticleDomain(slug: String, locale: String?, domainId: String?): DomainChoice? {
        val text = api.send(HttpMethod.Put, "$base/articles/${slug.encodeURLPathPart()}/domain", buildJsonObject {
            put("domain_id", domainId)
            if (locale != null) put("locale", locale)
        }, timeoutMillis = 15_000).bodyAsText()
        return MauriceJson.decodeFromString(Filed.serializer(), text).domain
    }

    suspend fun notes(): NotesFeed = NotesFeed.parse(api.send(HttpMethod.Get, "$base/entries?view=notes", timeoutMillis = 30_000).bodyAsText())

    suspend fun entry(entry: ShelfEntry): EntryPage? = entryPath(entry, "")?.let { page(HttpMethod.Get, it) }

    suspend fun entry(collection: String, locale: String, slug: String): EntryPage =
        page(HttpMethod.Get, "$base/entries/$collection/$locale/${slug.encodeURLPathPart()}")

    /** A conversation with Maurice about an entry (`POST /api/conversations {entry}`); answers its id. */
    suspend fun startConversation(entryId: String): String = newConversation(buildJsonObject { put("entry", entryId) })

    /**
     * A conversation held from the book reader (`{book, chapter | highlight}`):
     * the book's entry (made if it had none), its fiche, the book up to where the
     * member is, and where it starts — the chapter open there, or a passage just
     * highlighted, which then carries the conversation.
     */
    suspend fun startBookConversation(bookId: Int, chapterSlug: String?, highlightId: Int? = null): String =
        newConversation(buildJsonObject {
            put("book", bookId)
            if (highlightId != null) put("highlight", highlightId) else if (chapterSlug != null) put("chapter", chapterSlug)
        })

    /** A conversation on a passage highlighted in the member's own article (`{entry, article_highlight}`). */
    suspend fun startArticleConversation(locale: String, slug: String, highlightId: Int): String =
        newConversation(buildJsonObject { put("entry", "articles/$locale/$slug"); put("article_highlight", highlightId) })

    private suspend fun newConversation(body: JsonObject): String =
        api.post<JsonObject>("/api/conversations", body)["id"]
            .let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: throw ApiError(0, "No conversation.")

    /** The article's summaries (short and long), written now if [force] or if stale. Seconds: an LLM pass. */
    suspend fun summaries(slug: String, locale: String, force: Boolean = false): ShelfSource.Summary {
        val r: ArticleSummaries = api.post("$base/articles/${slug.encodeURLPathPart()}/summary", buildJsonObject { put("locale", locale); put("force", force) }, timeoutMillis = 180_000)
        return ShelfSource.Summary(r.summary, r.summaryLong, current = true)
    }

    suspend fun conversations(entry: ShelfEntry): List<EntryConversation> {
        if (entry.isForeign) return emptyList()
        val path = entryPath(entry, "/conversations") ?: return emptyList()
        return api.get<Conversations>(path).conversations
    }

    suspend fun conversationSummary(entry: ShelfEntry, conversationId: String, force: Boolean): EntryConversation.Summary {
        val path = entryPath(entry, "/conversations/$conversationId/summary") ?: throw ApiError(0, "No entry.")
        return api.post<ConversationSummary>(path, buildJsonObject { put("force", force) }, timeoutMillis = 180_000).summary
    }

    /** A note on the entry; on a book only in the library, the server makes its entry first. */
    suspend fun addNote(entry: ShelfEntry, text: String, quote: String? = null, page: String? = null): EntryPage {
        val path = entryPath(entry, "/notes")
        val body = noteBody(text, quote, page, null, if (path == null) entry.source?.bookId else null)
        return page(HttpMethod.Post, path ?: if (entry.source?.bookId != null) "$base/entries/from-book" else throw ApiError(0, "This entry cannot be written on."), body)
    }

    /** From the book reader: a note on the book's entry, made if need be. */
    suspend fun addBookNote(bookId: Int, text: String, quote: String?, chapterTitle: String?) {
        api.send(HttpMethod.Post, "$base/entries/from-book", noteBody(text, quote, null, chapterTitle, bookId), timeoutMillis = 30_000)
    }

    // Editing a note: PATCH / DELETE …/notes/:id[?owner=]. No outbox: an edit needs the network.

    /**
     * PATCH …/notes/:id — `{text, quote?, page?}`. Its answer (`{note}`) is
     * not needed: the caller reads the entry again.
     */
    suspend fun editNote(entry: ShelfEntry, noteId: String, text: String, quote: String?, page: String?) {
        val path = notePath(entry, noteId) ?: throw NoteEditError(NoteEditError.Kind.Changed)
        noteEdit(HttpMethod.Patch, path, buildJsonObject {
            put("text", text)
            if (!quote.isNullOrEmpty()) put("quote", quote)
            if (!page.isNullOrEmpty()) put("page", page)
        })
    }

    /** DELETE …/notes/:id — 204. */
    suspend fun deleteNote(entry: ShelfEntry, noteId: String) {
        val path = notePath(entry, noteId) ?: throw NoteEditError(NoteEditError.Kind.Changed)
        noteEdit(HttpMethod.Delete, path, null)
    }

    private fun notePath(entry: ShelfEntry, noteId: String): String? = entryPath(entry, "/notes/${noteId.encodeURLPathPart()}")

    private suspend fun noteEdit(method: HttpMethod, path: String, body: JsonObject?) {
        try {
            api.send(method, path, body, timeoutMillis = 30_000)
        } catch (e: ApiError) {
            when (e.status) {
                404 -> throw NoteEditError(NoteEditError.Kind.Changed)
                403 -> throw NoteEditError(NoteEditError.Kind.Forbidden)
                else -> throw e
            }
        }
    }

    suspend fun writeShared(entry: ShelfEntry, body: String, title: String?): EntryPage {
        val path = entryPath(entry, "/shared") ?: throw ApiError(0, "No entry to write on.")
        return page(HttpMethod.Put, path, buildJsonObject { put("body", body); if (!title.isNullOrBlank()) put("title", title) })
    }

    suspend fun setPublished(entry: ShelfEntry, on: Boolean): EntryPage {
        val path = entryPath(entry, "/shared/publish") ?: throw ApiError(0, "No entry to publish.")
        return page(if (on) HttpMethod.Post else HttpMethod.Delete, path)
    }

    suspend fun deployState(): DeployState = api.get("$base/site/deploy")

    suspend fun refreshDetails(entry: ShelfEntry): EntryPage {
        val path = entryPath(entry, "/details") ?: throw ApiError(0, "No entry to complete.")
        return page(HttpMethod.Post, path, timeout = 45_000)
    }

    /**
     * PUT …/book — the library's book this entry is about, named by the
     * member; answers the entry with that book as its source.
     */
    suspend fun linkBook(entry: ShelfEntry, bookId: Int): EntryPage {
        val path = entryPath(entry, "/book") ?: throw ApiError(0, "No entry to tie to a book.")
        return page(HttpMethod.Put, path, buildJsonObject { put("book_id", bookId) })
    }

    /** A cover: a JPEG of at most 1200 px on its longest side. */
    suspend fun setCover(entry: ShelfEntry, jpeg: ByteArray): EntryPage {
        val path = entryPath(entry, "/cover") ?: throw ApiError(0, "No entry to give a cover to.")
        val response = api.send(HttpMethod.Put, path, timeoutMillis = 30_000) { setBody(ByteArrayContent(jpeg, ContentType.Image.JPEG)) }
        return EntryPage.parse(response.bodyAsText())
    }

    suspend fun share(entry: ShelfEntry, memberId: String): EntryPage {
        val path = entryPath(entry, "/participants") ?: throw ApiError(0, "No entry to share.")
        return page(HttpMethod.Post, path, buildJsonObject { put("member_id", memberId) })
    }

    /** Null when the member took themself off it: the entry is no longer theirs to see. */
    suspend fun unshare(entry: ShelfEntry, memberId: String): EntryPage? {
        val path = entryPath(entry, "/participants/$memberId") ?: throw ApiError(0, "No entry.")
        val text = api.send(HttpMethod.Delete, path).bodyAsText()
        return runCatching { EntryPage.parse(text) }.getOrNull()
    }

    /** Both faces, in every locale. */
    suspend fun delete(entry: ShelfEntry) {
        val path = entryPath(entry, "") ?: throw ApiError(0, "Nothing to delete.")
        api.send(HttpMethod.Delete, path, timeoutMillis = 30_000)
    }

    suspend fun setArchived(entry: ShelfEntry, on: Boolean) {
        api.send(HttpMethod.Post, "$base/entries/archive", buildJsonObject { put("id", entry.id); put("archived", on) })
    }

    private suspend fun page(method: HttpMethod, path: String, body: JsonObject? = null, timeout: Long = 30_000): EntryPage =
        EntryPage.parse(api.send(method, path, body ?: if (method == HttpMethod.Post || method == HttpMethod.Put) buildJsonObject {} else null, timeoutMillis = timeout).bodyAsText())

    /** Another member's entry carries `?owner=` on every route that touches it. */
    private fun entryPath(entry: ShelfEntry, suffix: String): String? {
        val collection = entry.collection ?: return null
        val locale = entry.locale ?: return null
        val slug = entry.slug ?: return null
        val owner = entry.owner?.let { "?owner=" + it.username.encodeURLParameter() }.orEmpty()
        return "$base/entries/$collection/$locale/${slug.encodeURLPathPart()}$suffix$owner"
    }

    private fun noteBody(text: String, quote: String?, page: String?, chapterTitle: String?, bookId: Int?) = buildJsonObject {
        put("text", text)
        if (!quote.isNullOrEmpty()) put("quote", quote)
        if (!page.isNullOrEmpty() || !chapterTitle.isNullOrEmpty()) put("where", buildJsonObject {
            if (!page.isNullOrEmpty()) put("page", page)
            if (!chapterTitle.isNullOrEmpty()) put("chapter_title", chapterTitle)
        })
        if (bookId != null) put("book_id", bookId)
    }
}

