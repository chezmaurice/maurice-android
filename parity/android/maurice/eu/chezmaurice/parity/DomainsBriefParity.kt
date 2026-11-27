package eu.chezmaurice.parity

import eu.chezmaurice.core.model.BriefRefresh
import kotlin.test.Test

class DomainsBriefParity {
    @Test fun domainsBrief() = Parity.check("maurice", "domains-brief") { c ->
        val s = DomainsSupport.Scoped()
        val store = DomainsSupport.store(s)
        val result = mutableMapOf<String, Any?>()
        // As BriefSheet.kt calls the store.
        when (c.string("op")) {
            "load" -> {
                val page = runCatching { store.brief("d1") }.getOrNull()
                result += mapOf("found" to (page != null), "brief" to DomainsSupport.brief(page?.brief), "notes" to page?.notes?.let(DomainsSupport::notes))
            }
            "save" -> {
                val r = runCatching { store.saveBrief("d1", c.string("text").orEmpty()) }
                result += mapOf("ok" to r.isSuccess, "brief" to DomainsSupport.brief(r.getOrNull()))
            }
            "erase" -> result["ok"] = runCatching { store.deleteBrief("d1") }.getOrDefault(false)
            "refresh" -> result["outcome"] = when (val o = store.refreshBrief("d1")) {
                is BriefRefresh.Written -> "written:${o.sources}"
                BriefRefresh.Unchanged -> "unchanged"
                BriefRefresh.Capped -> "capped"
                is BriefRefresh.Failed -> "failed:${o.why}"
            }
        }
        s.settle()
        result["requests"] = DomainsSupport.requests { it.startsWith("/api/domains/d1/brief") }
        result
    }
}
