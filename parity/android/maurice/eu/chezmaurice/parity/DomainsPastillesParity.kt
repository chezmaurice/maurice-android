package eu.chezmaurice.parity

import eu.chezmaurice.core.model.Domain
import eu.chezmaurice.core.model.Message
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.maurice.domains.Pastille
import eu.chezmaurice.maurice.domains.pastilles
import eu.chezmaurice.parity.Parity.str
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test

class DomainsPastillesParity {
    @Test fun domainsPastilles() = Parity.check("maurice", "domains-pastilles") { c ->
        val rows = MauriceJson.decodeFromString(ListSerializer(Domain.serializer()), c.text("rows"))
        val convo = c.obj("conversation")!!
        val messages = MauriceJson.decodeFromString(ListSerializer(Message.serializer()), c.text("messages"))
        // As ChatScreen.kt calls it.
        val marks = pastilles(rows, "u1", convo["maurice_id"].str(), convo["maurice_bound_by"].str() == "auto", messages.flatMap { it.data.orEmpty() })
        mapOf("pastilles" to marks.map {
            mapOf("id" to it.domain.id, "kind" to when (it.kind) {
                Pastille.Kind.Bound -> "bound"
                Pastille.Kind.BoundAuto -> "boundAuto"
                Pastille.Kind.Read -> "read"
                Pastille.Kind.Recognised -> "recognised"
            })
        })
    }
}
