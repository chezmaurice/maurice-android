package eu.chezmaurice.parity

import eu.chezmaurice.carnet.entry.NoteEditTarget
import eu.chezmaurice.carnet.entry.NoteEdits
import eu.chezmaurice.carnet.loadCarnetStrings
import eu.chezmaurice.core.carnet.MineItem
import eu.chezmaurice.core.net.MauriceJson
import kotlin.test.Test

/** Rewriting or taking off a note (896f705); `ShelfNotesParity.swift`. */
class ShelfNotesParity {
    @Test fun noteEdit() = Parity.check("carnet", "shelf-note-edit") { c ->
        val strings = loadCarnetStrings("en")
        val item = try { MauriceJson.decodeFromString(MineItem.serializer(), c.text("item")) } catch (e: Throwable) {
            return@check mapOf("failed" to ShelfFixture.failure(e))
        }
        if (c.string("call") == "read") return@check mapOf(
            "canEdit" to item.canEdit, "canDelete" to item.canDelete, "edited" to item.edited,
            "editedLine" to item.edited?.takeIf { it.isNotEmpty() }?.let { NoteEdits.editedLine(it, strings) },
        )
        val garden = ShelfFixture.garden()
        val target = NoteEditTarget(ShelfFixture.entry(c), item)
        var reloads = 0
        val result: Any = try {
            when (c.string("call")) {
                "edit" -> NoteEdits.save(garden, target, c.string("text") ?: "", c.string("quote"), c.string("page")) { reloads++ }
                "delete" -> NoteEdits.delete(garden, target) { reloads++ }
            }
            "done"
        } catch (e: Throwable) {
            if (e is AssertionError) throw e
            mapOf("failed" to NoteEdits.message(e, strings))
        }
        mapOf("requests" to ShelfObserve.requests(), "result" to result, "reloads" to reloads)
    }
}
