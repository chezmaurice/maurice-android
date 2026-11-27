package eu.chezmaurice.core.composer

import eu.chezmaurice.core.domains.flag
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What Maurice may be given to read: the composer's six kinds. */
enum class ItemType(val wire: String) {
    Note("note"), Book("book"), Conversation("conversation"), File("file"), Folder("folder"), Fiche("fiche");

    companion object {
        fun of(wire: String?): ItemType? = entries.firstOrNull { it.wire == wire }
    }
}

/** The context budget, in tokens (chars / 4). */
const val CONTEXT_BUDGET = 200_000

/**
 * One thing in the context tray (`TrayItem`). [rawId] is a note's slug, a
 * book's numeric id, a conversation's, file's or folder's id, a fiche's
 * `collection/locale/slug`. The options are what the server takes; the rest
 * is read off `/weigh` and the saved spec, never sent.
 */
data class TrayItem(
    val type: ItemType,
    val rawId: String,
    val title: String,
    val sub: String = "",
    // note / folder
    val recurse: Boolean = false,
    val includeArchived: Boolean = false,
    val exclude: List<String> = emptyList(),
    // book
    val representation: String = "summary",
    /** all | up_to | chapters | progress. */
    val scopeMode: String = "all",
    val uptoChapter: Int = 0,
    val selectedRefs: List<String> = emptyList(),
    /** Progress scope: the visible chapter reached, -1 before the reader starts. */
    val progressChapter: Int = -1,
    val progressPaused: Boolean = false,
    val visibleChapters: Int = 0,
    // fiche
    val includeFragments: Boolean = true,
    val fragmentCount: Int = 0,
    // conversation: a long one is a summary unless pinned to its transcript
    val conversationFull: Boolean = false,
    val summarisable: Boolean = false,
    /** ready | stale | pending | none. */
    val summaryStatus: String = "none",
    val fullWeight: Int = 0,
    val uncovered: Int = 0,
    // file
    /** text | img | pdf | file. */
    val kind: String = "",
    /** A binary file: no token estimate, it rides as an attachment. */
    val na: Boolean = false,
    // flags
    val encrypted: Boolean = false,
    val moc: Boolean = false,
    // from /weigh
    val weight: Int = 0,
    val count: Int? = null,
    val heavy: Boolean = false,
    val binaryCount: Int = 0,
) {
    val id: String get() = "${type.wire}-$rawId"

    /** The item as the server takes it, for weigh and save. */
    fun payload(): JsonObject = when (type) {
        ItemType.Note -> buildJsonObject {
            put("type", "note"); put("id", rawId); put("recurse", recurse)
            put("include_archived", includeArchived); put("exclude", strings(exclude))
        }
        ItemType.Book -> buildJsonObject {
            put("type", "book"); put("id", rawId.toIntOrNull() ?: 0)
            // A book that follows the reader is loaded as summaries: the full text
            // of everything read outgrows the budget long before the end.
            put("representation", if (scopeMode == "progress") "summary" else representation)
            put("scope", buildJsonObject {
                put("mode", scopeMode)
                if (scopeMode == "up_to") put("chapter", uptoChapter)
                if (scopeMode == "chapters") put("refs", strings(selectedRefs))
            })
        }
        ItemType.Fiche -> buildJsonObject { put("type", "fiche"); put("id", rawId); put("include_fragments", includeFragments) }
        ItemType.Conversation -> buildJsonObject {
            put("type", "conversation"); put("id", rawId)
            if (conversationFull) put("representation", "full")
        }
        ItemType.File -> buildJsonObject { put("type", "file"); put("id", rawId) }
        ItemType.Folder -> buildJsonObject { put("type", "folder"); put("id", rawId); put("recurse", recurse); put("exclude", strings(exclude)) }
    }

    /** What `/weigh` says of it, in request order. */
    fun weighed(w: JsonObject): TrayItem {
        var it = copy(
            weight = w.int("weight") ?: 0,
            count = w.int("count"),
            heavy = w.bool("heavy") ?: false,
            binaryCount = w.int("binaryCount") ?: 0,
        )
        w.str("title")?.takeIf { s -> s.isNotEmpty() }?.let { t -> it = it.copy(title = t) }
        w.bool("moc")?.let { m -> it = it.copy(moc = m) }
        w.bool("encrypted")?.let { e -> it = it.copy(encrypted = e) }
        w.int("progressChapter")?.let { p -> it = it.copy(progressChapter = p) }
        w.bool("progressPaused")?.let { p -> it = it.copy(progressPaused = p) }
        w.int("fragments")?.let { f -> it = it.copy(fragmentCount = f) }
        when (type) {
            ItemType.Conversation -> it = it.copy(
                summarisable = w.bool("summarisable") ?: false,
                summaryStatus = w.str("summary") ?: "none",
                fullWeight = w.int("fullWeight") ?: 0,
                uncovered = w.int("uncovered") ?: 0,
            )
            ItemType.Book -> w.int("visibleCount")?.let { v -> it = it.copy(visibleChapters = v) }
            ItemType.File -> {
                val kind = w.str("kind") ?: it.kind
                it = it.copy(kind = kind, na = w.bool("na") ?: it.na, sub = listOf(kind, w.str("path").orEmpty()).filter { s -> s.isNotEmpty() }.joinToString(" · "))
            }
            else -> {}
        }
        return it
    }

    companion object {
        /** A saved item (with its `snapshot`), as the spec and a domain's context hold it. */
        fun fromSpec(d: JsonObject): TrayItem? {
            val type = ItemType.of(d.str("type")) ?: return null
            // A string or a number; anything else is no id (iOS makes one up: "note-1", "note-<null>").
            val rawId = (d["id"] as? JsonPrimitive)?.takeIf { it.isString || it.booleanOrNull == null }?.contentOrNull ?: return null
            val snap = d["snapshot"] as? JsonObject
            val base = TrayItem(type, rawId, title = rawId)
            return when (type) {
                ItemType.Note -> base.copy(
                    recurse = d.bool("recurse") ?: false, includeArchived = d.bool("include_archived") ?: false,
                    exclude = d.strings("exclude"), encrypted = snap?.bool("encrypted") ?: false,
                )
                ItemType.Book -> {
                    val scope = d["scope"] as? JsonObject
                    base.copy(
                        representation = d.str("representation") ?: "summary",
                        scopeMode = scope?.str("mode") ?: "all",
                        uptoChapter = scope?.int("chapter") ?: 0,
                        selectedRefs = scope?.strings("refs").orEmpty(),
                        progressChapter = snap?.int("progressChapter") ?: -1,
                    )
                }
                ItemType.Fiche -> base.copy(includeFragments = d.bool("include_fragments") ?: true, fragmentCount = snap?.int("fragments") ?: 0)
                ItemType.Conversation -> base.copy(
                    conversationFull = d.str("representation") == "full",
                    summaryStatus = snap?.str("summary") ?: "none", fullWeight = snap?.int("fullWeight") ?: 0,
                )
                ItemType.File -> base.copy(kind = snap?.str("kind").orEmpty(), na = snap?.bool("na") ?: false)
                ItemType.Folder -> base.copy(recurse = d.bool("recurse") ?: true, exclude = d.strings("exclude"))
            }.copy(weight = snap?.int("weight") ?: 0, count = snap?.int("count"))
        }

        /** A fresh item from the picker: notes recurse when they are a MOC, folders always. */
        fun from(e: SearchEntry): TrayItem = TrayItem(
            type = e.type, rawId = e.rawId, title = e.title, sub = e.sub, encrypted = e.encrypted, moc = e.moc, kind = e.kind,
            recurse = when (e.type) { ItemType.Note -> e.moc; ItemType.Folder -> true; else -> false },
            na = e.type == ItemType.File && (e.kind == "img" || e.kind == "pdf"),
        )

        private fun strings(list: List<String>) = JsonArray(list.map(::JsonPrimitive))
    }
}

/** A result of the picker's search (`/api/v1/composer/search`). */
data class SearchEntry(
    val type: ItemType,
    val rawId: String,
    val title: String,
    val sub: String = "",
    val encrypted: Boolean = false,
    val moc: Boolean = false,
    val kind: String = "",
    /** ISO; null when the source has no date to give. */
    val updatedAt: String? = null,
) {
    val id: String get() = "${type.wire}-$rawId"
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
/** A flag as Swift's `as? Bool` reads it (1 and 0 included), and its string besides. */
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.flag()
internal fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
internal fun JsonElement.objects(): List<JsonObject> = (this as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
internal val JsonElement.text: String? get() = runCatching { jsonPrimitive.contentOrNull }.getOrNull()
