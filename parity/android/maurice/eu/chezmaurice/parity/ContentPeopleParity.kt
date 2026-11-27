package eu.chezmaurice.parity

import eu.chezmaurice.core.chat.PersonRef
import eu.chezmaurice.core.chat.PersonReviewModel
import eu.chezmaurice.core.chat.withoutSources
import eu.chezmaurice.core.net.MauriceJson
import kotlin.test.Test

/** A person fiche reviewed in the app (content-people.json). */
class ContentPeopleParity {
    @Test fun contentPeople() = Parity.check("maurice", "content-people") { c ->
        when (c.string("op")) {
            "ref" -> PersonRef.fromGardenUrl(c.string("url").orEmpty())?.let { mapOf("locale" to it.locale, "basename" to it.basename, "anchor" to it.anchor, "id" to it.id) }
            "sources" -> mapOf("text" to withoutSources(c.string("text").orEmpty()))
            "model" -> runCatching { MauriceJson.decodeFromString(PersonReviewModel.serializer(), c.text("json")) }.getOrNull()?.let(::model) ?: "undecodable"
            else -> mapOf("unknown op" to c.string("op"))
        }
    }

    private fun model(m: PersonReviewModel): Map<String, Any?> = mapOf(
        "title" to m.title, "locale" to m.locale, "basename" to m.basename, "status" to m.status, "byMaurice" to m.byMaurice,
        "relation" to mapOf(
            "text" to m.relation.text, "status" to m.relation.status, "since" to m.relation.since, "until" to m.relation.until, "edited" to m.relation.edited,
            "proposed" to m.relation.proposed?.let { mapOf("text" to it.text, "since" to it.since, "until" to it.until) },
        ),
        "identities" to m.identities.map { mapOf("address" to it.address, "mailboxes" to it.mailboxes, "status" to it.status, "source" to it.source, "conflict" to it.conflict) },
        "fragments" to m.fragments.map {
            mapOf("id" to it.id, "summary" to it.summary, "status" to it.status, "origin" to it.origin, "address" to it.address,
                "mailbox" to it.mailbox, "body" to it.body, "edited" to it.edited)
        },
        "exchanges" to m.exchanges, "pending" to m.pending,
    )
}
