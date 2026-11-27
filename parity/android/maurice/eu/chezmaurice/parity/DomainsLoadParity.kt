package eu.chezmaurice.parity

import kotlin.test.Test

class DomainsLoadParity {
    @Test fun domainsLoad() = Parity.check("maurice", "domains-load") { _ ->
        val s = DomainsSupport.Scoped()
        val store = DomainsSupport.store(s)
        store.load().join()
        val st = store.state.value
        val resolved = mutableMapOf<String, Any?>("__everyday__" to st.resolvedModel(null)?.name)
        for (d in st.rows) resolved[d.id] = st.resolvedModel(d)?.name
        // The proposals are ChatService's on iOS (DomainsProposalsParity): left out here.
        val requests = DomainsSupport.requests { !it.startsWith("/api/domains/proposals") }
        mapOf(
            "requests" to requests,
            "rows" to st.rows.map(DomainsSupport::row),
            "domains" to st.domains.map { it.id },
            "companions" to st.companions.map { it.id },
            "overview" to st.overview.mapValues { DomainsSupport.overview(it.value) },
            "models" to st.models.map(DomainsSupport::model),
            "defaultModel" to st.defaultModelId,
            "everydayModel" to st.everydayModelId,
            "families" to st.families.map(DomainsSupport::family),
            "resolvedModel" to resolved,
        )
    }
}
