package eu.chezmaurice.parity

import eu.chezmaurice.core.model.ProposalDecision
import eu.chezmaurice.parity.Parity.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.test.Test

class DomainsProposalsParity {
    @Test fun domainsProposals() = Parity.check("maurice", "domains-proposals") { c ->
        val s = DomainsSupport.Scoped()
        val store = DomainsSupport.store(s)
        store.loadProposals()
        var result: Any? = null
        fun strings(key: String) = c.array(key).orEmpty().mapNotNull { it.str() }
        when (c.string("op")) {
            "seen" -> store.markProposalsSeen()
            "apply" -> {
                val items = c.array("items").orEmpty().map { it as JsonObject }.map {
                    ProposalDecision(it["id"].str().orEmpty(), it["action"].str().orEmpty(), name = it["name"].str(), summary = it["summary"].str(),
                        seed = (it["seed"] as? JsonPrimitive)?.booleanOrNull ?: false)
                }
                result = store.apply(items)?.let { r ->
                    mapOf("adopted" to r.adopted.map { mapOf("id" to it.id, "domainId" to it.domainId) },
                        "errors" to r.errors.map { mapOf("id" to it.id, "error" to it.error) })
                }
            }
            "restore" -> result = store.restore(c.string("id").orEmpty())
            "merge" -> result = store.merge(strings("ids"), c.string("name"))
            "split" -> result = store.split(c.string("id").orEmpty(), c.string("name").orEmpty(), strings("conversations"), strings("mail"))
            "detail" -> result = store.proposal(c.string("id").orEmpty())?.let { d ->
                mapOf("id" to d.id, "name" to d.name, "summary" to d.summary, "state" to d.state,
                    "conversations" to d.conversationsList.map { mapOf("id" to it.id, "date" to it.date, "title" to it.title) },
                    "mail" to d.mailList.map { mapOf("path" to it.path, "from" to it.from, "to" to it.to, "title" to it.title) })
            }
        }
        s.settle()
        val st = store.state.value
        mapOf(
            "requests" to DomainsSupport.requests { it.startsWith("/api/domains/proposals") },
            "result" to result,
            "open" to st.proposals.map(DomainsSupport::proposal),
            "settled" to st.settled.map { it.id },
            "restorable" to st.settled.filter { it.canRestore }.map { it.id },
            "unseen" to st.unseen,
        )
    }
}
