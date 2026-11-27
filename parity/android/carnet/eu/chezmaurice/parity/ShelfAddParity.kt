package eu.chezmaurice.parity

import eu.chezmaurice.carnet.shelf.AddEntry
import eu.chezmaurice.core.carnet.EntrySuggestion
import eu.chezmaurice.core.carnet.LibraryUnavailable
import eu.chezmaurice.core.carnet.SuggestionList
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test

/** The shelf's « + » (AddEntrySheet.kt — AddEntry); `ShelfAddParity.swift`. */
class ShelfAddParity {
    private fun row(s: EntrySuggestion) = mapOf(
        "id" to s.id, "kind" to s.kind, "title" to s.title, "year" to s.year, "subtitle" to s.subtitle,
        "image" to s.image, "note" to s.note, "state" to s.state, "existing" to s.existing,
        "candidates" to s.candidates.map { mapOf("id" to it.id, "title" to it.title, "year" to it.year, "subtitle" to it.subtitle) },
        "entry" to s.entry, "searchable" to s.searchable, "isKept" to s.isKept,
    )

    private fun list(l: SuggestionList) = mapOf("batch" to l.batch, "rows" to l.suggestions.map(::row), "pending" to l.pending, "kept" to l.kept)

    /** The multipart body carries a boundary of each side's own: compared, where the file went and how. */
    private suspend fun requests(file: Boolean): List<Any?> {
        val all = ShelfObserve.requests()
        return if (!file) all else all.map { r -> (r as? JsonObject)?.let { JsonObject(it - "body") } ?: r }
    }

    @Test fun addEntry() = Parity.check("carnet", "shelf-add-entry") { c ->
        val session = ShelfFixture.session()
        val add = AddEntry(session.api()!!)
        val call = c.string("call") ?: ""
        val result: Any = try {
            when (call) {
                "ask" -> list(add.ask(c.string("text") ?: "", c.string("locale")))
                "reread" -> list(add.suggestions.batch(c.string("batch") ?: ""))
                "keep" -> mapOf("open" to add.keep(EntrySuggestion(c.string("id") ?: "", "", ""), c.string("candidate"), emptyList()))
                "addFile" -> add.addFile((c.string("bytes") ?: "").encodeToByteArray(), c.string("filename") ?: "")
                    .let { (ref, r) -> mapOf("open" to ref, "alreadyThere" to r.alreadyThere) }
                else -> "unknown call"
            }
        } catch (e: LibraryUnavailable) {
            mapOf("failed" to "unavailable: " + e.message)
        } catch (e: Throwable) {
            if (e is AssertionError) throw e
            mapOf("failed" to ShelfFixture.failure(e))
        }
        mapOf("requests" to requests(call == "addFile"), "result" to result)
    }
}
