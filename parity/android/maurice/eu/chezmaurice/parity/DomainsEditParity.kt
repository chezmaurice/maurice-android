package eu.chezmaurice.parity

import eu.chezmaurice.core.domains.SaveOutcome
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test

class DomainsEditParity {
    @Test fun domainsEdit() = Parity.check("maurice", "domains-edit") { c ->
        val s = DomainsSupport.Scoped()
        val store = DomainsSupport.store(s)
        store.reloadRows().join()   // the list as it stands: rows, then the overview
        var outcome: Any? = null
        when (c.string("op")) {
            "save" -> {
                // The body is the DomainEditor Composable's to build; not compared (see the vector's about).
                val fields = c.obj("draft") ?: JsonObject(emptyMap())
                outcome = when (val o = store.save(if (c.bool("new") == true) null else c.string("id"), fields)) {
                    is SaveOutcome.Saved -> "saved:${o.row.id}"
                    is SaveOutcome.NameTaken -> "nameTaken:${o.name}"
                    SaveOutcome.Failed -> "failed"
                }
            }
            "setModel" -> store.setModel(c.string("model").orEmpty(), store.state.value.row(c.string("id"))).join()
            "delete" -> {
                // As the editor calls it: runCatching { store.delete(id) }.
                runCatching { store.delete(c.string("id").orEmpty()) }
                outcome = "done"
            }
        }
        s.settle()
        var log = DomainsSupport.requests()
        if (c.string("op") == "save") log = DomainsSupport.withoutBodies(log)
        val st = store.state.value
        mapOf(
            "requests" to log,
            "outcome" to outcome,
            "rows" to st.rows.map { mapOf("id" to it.id, "name" to it.name, "kind" to it.kind, "model" to it.model) },
            "overview" to st.overview.keys.sorted(),
            "everydayModel" to st.everydayModelId,
        )
    }
}
