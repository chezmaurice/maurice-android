package eu.chezmaurice.core.composer

import eu.chezmaurice.core.net.ApiClient
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A note's subtree as the server resolves it against an item's options. */
@Serializable
data class NoteNode(
    val id: String = "",
    val title: String = "",
    val moc: Boolean = false,
    val archived: Boolean = false,
    val encrypted: Boolean = false,
    val weight: Int = 0,
    val childCount: Int = 0,
    val included: Boolean = false,
    val excluded: Boolean = false,
    val children: List<NoteNode> = emptyList(),
)

@Serializable
data class NoteResolve(val resolved: Resolved = Resolved(), val tree: NoteNode = NoteNode()) {
    @Serializable
    data class Resolved(
        val count: Int = 1, val weight: Int = 0, val encrypted: Boolean = false,
        val archivedWithheld: Int = 0, val manualExcluded: Int = 0, val hasChildren: Boolean = false,
    )
}

@Serializable
data class FileNode(
    val id: String = "",
    val name: String = "",
    val isFolder: Boolean = false,
    val kind: String = "file",
    val weight: Int = 0,
    val na: Boolean = false,
    val childCount: Int = 0,
    val included: Boolean = false,
    val excluded: Boolean = false,
    val children: List<FileNode> = emptyList(),
)

@Serializable
data class FolderResolve(
    val weight: Int = 0, val count: Int = 0, val textCount: Int = 0, val binaryCount: Int = 0,
    val hasChildren: Boolean = false, val manualExcluded: Int = 0, val tree: FileNode = FileNode(),
)

@Serializable
data class BookChapter(
    val index: Int = 0,
    val ref: String = "",
    val name: String = "",
    val hidden: Boolean = false,
    /** front_matter | body | back_matter. */
    @SerialName("section_type") val sectionType: String = "body",
    @SerialName("word_count") val wordCount: Int = 0,
    @SerialName("has_summary") val hasSummary: Boolean = false,
)

@Serializable private data class Chapters(val chapters: List<BookChapter> = emptyList())

/** What `/weigh` and a save answer: the items in request order, and the total. */
data class Weighing(val items: List<JsonObject>, val total: Int, val over: Boolean, val tier: String)

/** The composer routes, `/api/v1/composer/…` (`ComposerStore.swift`). */
class ComposerApi(private val api: ApiClient) {

    suspend fun weigh(items: List<TrayItem>): Weighing {
        val r: JsonObject = api.post("/api/v1/composer/weigh", body(items))
        return Weighing(
            items = r["items"]?.objects().orEmpty(),
            total = r.int("total") ?: 0, over = r.bool("over") ?: false, tier = r.str("tier") ?: "light",
        )
    }

    /** The conversation's saved context; empty when it has none. */
    suspend fun spec(conversationId: String): List<TrayItem> {
        val r: JsonObject = api.get("/api/v1/composer/context/$conversationId")
        return r["items"]?.objects().orEmpty().mapNotNull(TrayItem::fromSpec)
    }

    suspend fun save(conversationId: String, items: List<TrayItem>) {
        api.send(HttpMethod.Put, "/api/v1/composer/context/$conversationId", body(items))
    }

    /** Long-polls until the conversation's summary exists (the server idles out at 255 s). */
    suspend fun awaitSummary(conversationId: String) {
        api.send(HttpMethod.Get, "/api/v1/composer/conversations/$conversationId/summary?wait=1", timeoutMillis = 270_000)
    }

    suspend fun search(q: String): List<SearchEntry> {
        val r: JsonObject = api.get("/api/v1/composer/search?q=${q.encodeURLParameter()}")
        return r["results"]?.objects().orEmpty().mapNotNull { e ->
            val type = ItemType.of(e.str("type")) ?: return@mapNotNull null
            val id = (e["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val badges = e["badges"] as? JsonObject
            SearchEntry(
                type, id, title = e.str("title") ?: id, sub = e.str("sub").orEmpty(),
                encrypted = badges?.bool("encrypted") ?: false, moc = badges?.bool("moc") ?: false,
                kind = badges?.str("kind").orEmpty(), updatedAt = e.str("updated_at")?.takeIf(::isIsoInstant),
            )
        }
    }

    suspend fun resolveNote(item: TrayItem): NoteResolve = api.get(
        "/api/v1/composer/notes/${item.rawId.encodeURLParameter()}/resolve?recurse=${item.recurse}" +
            "&include_archived=${item.includeArchived}&exclude=${item.exclude.joinToString(",").encodeURLParameter()}",
    )

    suspend fun resolveFolder(item: TrayItem): FolderResolve = api.get(
        "/api/v1/composer/folders/${item.rawId}/resolve?recurse=${item.recurse}&exclude=${item.exclude.joinToString(",").encodeURLParameter()}",
    )

    suspend fun chapters(bookId: String): List<BookChapter> = api.get<Chapters>("/api/v1/composer/books/$bookId/chapters").chapters

    /** Hold the reader's marker where it is, or let it move again. */
    suspend fun setReadingPaused(bookId: String, paused: Boolean) {
        api.send(HttpMethod.Post, "/api/v1/calibre/books/$bookId/reading-progress/tracking", buildJsonObject { put("paused", paused) })
    }

    suspend fun resetReadingProgress(bookId: String) {
        api.send(HttpMethod.Delete, "/api/v1/calibre/books/$bookId/reading-progress")
    }

    /** `{"families": [...]}` overrides the conversation's tools; null inherits. */
    suspend fun conversationFamilies(conversationId: String): List<String> {
        val r: JsonObject = api.get("/api/conversations/$conversationId/tool-families")
        return r.strings("selected")
    }

    suspend fun setConversationFamilies(conversationId: String, families: List<String>?) {
        api.send(HttpMethod.Patch, "/api/conversations/$conversationId/tool-families", buildJsonObject {
            put("families", families?.let { JsonArray(it.map(::JsonPrimitive)) } ?: kotlinx.serialization.json.JsonNull)
        })
    }

    /** ISO 8601 with its zone, with or without fractions: what iOS reads as a date; anything else is none. */
    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun isIsoInstant(raw: String): Boolean = runCatching { kotlin.time.Instant.parse(raw) }.isSuccess

    private fun body(items: List<TrayItem>) = buildJsonObject { put("items", JsonArray(items.map { it.payload() })) }
}
