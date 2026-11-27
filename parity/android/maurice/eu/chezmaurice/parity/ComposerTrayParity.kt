package eu.chezmaurice.parity

import eu.chezmaurice.core.composer.ComposerApi
import eu.chezmaurice.core.composer.ComposerStore
import eu.chezmaurice.core.composer.FileNode
import eu.chezmaurice.core.composer.ItemType
import eu.chezmaurice.core.composer.NoteNode
import eu.chezmaurice.core.composer.SearchEntry
import eu.chezmaurice.parity.Parity.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.test.Test

class ComposerTrayParity {
    @Test fun composerTray() = Parity.check("maurice", "composer-tray") { c ->
        val s = DomainsSupport.Scoped()
        val store = ComposerStore(ComposerApi(MauriceFixture.session().api()!!), s.scope)
        val results = mutableListOf<Any?>()
        fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
        fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
        fun JsonObject.strings(k: String) = (this[k] as? JsonArray)?.mapNotNull { it.str() }
        for (step in c.array("steps").orEmpty().map { it as JsonObject }) {
            when {
                "bind" in step -> {
                    val locked = step["locked"] as? JsonObject ?: JsonObject(emptyMap())
                    store.bind(step["bind"].takeUnless { it is JsonNull }?.str(), lockedName = locked["name"].str().orEmpty(),
                        lockedCount = locked.int("count") ?: 0, lockedWeight = locked.int("weight") ?: 0)
                }
                // A blank page's first question created it (ChatController.onCreated, wired by HomeScreen).
                "created" in step -> store.carryTo(step["created"].str().orEmpty())
                "add" in step -> store.add(entry(step["add"] as JsonObject))
                "toggle" in step -> store.toggle(entry(step["toggle"] as JsonObject))
                "remove" in step -> store.remove(step["remove"].str().orEmpty())
                "update" in step -> store.state.value.items.firstOrNull { it.id == step["update"].str() }?.let { start ->
                    val set = step["set"] as? JsonObject ?: JsonObject(emptyMap())
                    var it = start
                    set.bool("recurse")?.let { v -> it = it.copy(recurse = v) }
                    set.bool("includeArchived")?.let { v -> it = it.copy(includeArchived = v) }
                    set.strings("exclude")?.let { v -> it = it.copy(exclude = v) }
                    set.bool("conversationFull")?.let { v -> it = it.copy(conversationFull = v) }
                    set["scopeMode"].str()?.let { v -> it = it.copy(scopeMode = v) }
                    set.int("uptoChapter")?.let { v -> it = it.copy(uptoChapter = v) }
                    set.strings("selectedRefs")?.let { v -> it = it.copy(selectedRefs = v) }
                    set["representation"].str()?.let { v -> it = it.copy(representation = v) }
                    set.bool("includeFragments")?.let { v -> it = it.copy(includeFragments = v) }
                    store.update(it)
                }
                "pause" in step -> {
                    val p = step["pause"] as JsonObject
                    store.setReadingPaused(p["book"].str().orEmpty(), p.bool("paused") ?: false).join()
                }
                "reset" in step -> store.resetReadingProgress(step["reset"].str().orEmpty()).join()
                "search" in step -> results.add(store.search(step["search"].str().orEmpty())?.map(ComposerSupport::entry))
                "resolveNote" in step -> {
                    val it = store.state.value.items.firstOrNull { i -> i.id == step["resolveNote"].str() }
                    results.add(if (it == null) "no such item" else store.resolveNote(it)?.let { r ->
                        mapOf("count" to r.resolved.count, "weight" to r.resolved.weight, "encrypted" to r.resolved.encrypted,
                            "archivedWithheld" to r.resolved.archivedWithheld, "manualExcluded" to r.resolved.manualExcluded,
                            "hasChildren" to r.resolved.hasChildren, "tree" to note(r.tree))
                    })
                }
                "resolveFolder" in step -> {
                    val it = store.state.value.items.firstOrNull { i -> i.id == step["resolveFolder"].str() }
                    results.add(if (it == null) "no such item" else store.resolveFolder(it)?.let { r ->
                        mapOf("weight" to r.weight, "count" to r.count, "textCount" to r.textCount, "binaryCount" to r.binaryCount,
                            "hasChildren" to r.hasChildren, "manualExcluded" to r.manualExcluded, "tree" to file(r.tree))
                    })
                }
                "chapters" in step -> results.add(store.bookChapters(step["chapters"].str().orEmpty()).map {
                    mapOf("index" to it.index, "ref" to it.ref, "name" to it.name, "hidden" to it.hidden, "sectionType" to it.sectionType,
                        "wordCount" to it.wordCount, "hasSummary" to it.hasSummary)
                })
            }
            s.settle()
        }
        val st = store.state.value
        mapOf(
            "requests" to DomainsSupport.requests(),
            "results" to results,
            "items" to st.items.map(ComposerSupport::item),
            "weights" to st.items.associate { it.id to it.weight },
            "counts" to st.items.associate { it.id to it.count },
            "heavy" to st.items.associate { it.id to it.heavy },
            "total" to st.total, "tier" to st.tier, "over" to st.over,
            "displayTotal" to st.displayTotal, "attachmentCount" to st.attachmentCount,
            "locked" to mapOf("name" to st.lockedName, "count" to st.lockedCount, "weight" to st.lockedWeight),
        )
    }

    private fun entry(d: JsonObject) = SearchEntry(
        type = ItemType.of(d["type"].str()) ?: ItemType.Note, rawId = d["id"].str().orEmpty(), title = d["title"].str().orEmpty(),
        sub = d["sub"].str().orEmpty(), encrypted = (d["encrypted"] as? JsonPrimitive)?.booleanOrNull ?: false,
        moc = (d["moc"] as? JsonPrimitive)?.booleanOrNull ?: false, kind = d["kind"].str().orEmpty(),
    )

    private fun note(n: NoteNode): Map<String, Any?> = mapOf(
        "id" to n.id, "title" to n.title, "moc" to n.moc, "archived" to n.archived, "weight" to n.weight, "childCount" to n.childCount,
        "included" to n.included, "excluded" to n.excluded, "children" to n.children.map(::note),
    )

    private fun file(n: FileNode): Map<String, Any?> = mapOf(
        "id" to n.id, "name" to n.name, "isFolder" to n.isFolder, "kind" to n.kind, "weight" to n.weight, "na" to n.na,
        "included" to n.included, "excluded" to n.excluded, "children" to n.children.map(::file),
    )
}
