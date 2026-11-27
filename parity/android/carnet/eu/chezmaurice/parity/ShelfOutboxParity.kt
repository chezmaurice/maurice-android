package eu.chezmaurice.parity

import eu.chezmaurice.carnet.share.flushOutboxes
import eu.chezmaurice.core.carnet.Outbox
import eu.chezmaurice.core.model.Household
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.session.Session
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test

/**
 * The outbox sent again; `ShelfOutboxParity.swift`. Carnet keeps one outbox
 * per member of a household (`offlineKey(scope, "outbox")`) and sends them
 * all, each as its member (`flushOutboxes`, what ShareHost does on return).
 */
class ShelfOutboxParity {
    @Test fun outbox() = Parity.check("carnet", "shelf-outbox") { c ->
        // The household as the picker knows it: both members; u2 signed in here only when the case says so.
        val store = MauriceFixture.store(prefix = "carnet").apply {
            val household = Household(
                id = MauriceFixture.HOUSEHOLD_ID, serverUrl = Parity.baseUrl, name = MauriceFixture.HOUSEHOLD_NAME,
                members = listOf(Member(id = "u1", displayName = "Alice"), Member(id = "u2", displayName = "Bob")),
                activeMemberId = "u1",
            )
            put("carnet.households", MauriceJson.encodeToString(ListSerializer(Household.serializer()), listOf(household)))
            if (c.bool("otherSignedIn") == true) putSecret("carnet.token.${MauriceFixture.HOUSEHOLD_ID}.u2", "tok-u2")
        }
        val session = Session(store, "carnet")
        val boxes = mutableMapOf<String, String?>()
        fun box(member: String) = Outbox({ boxes[member] }, { boxes[member] = it })
        // The language ShareHost stamps an item with: the device's, fr or en.
        val lang = java.util.Locale.getDefault().language.takeIf { it == "fr" || it == "en" } ?: "en"
        c.array("items").orEmpty().forEachIndexed { i, raw ->
            val o = raw.jsonObject
            fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            val member = s("member") ?: "u1"
            box(member).add(Outbox.Item("i$i", s("url")!!, s("title"), s("comment"), lang, 1_800_000_000_000L + (o["at"]?.jsonPrimitive?.longOrNull ?: 0L) * 1000))
        }
        flushOutboxes(session, outboxOf = { scope -> box(scope.substringAfter('#')) }, booksOf = { ShelfBooks.box() })
        val requests = ShelfObserve.requests(prefix = "/api/v1/garden/articles")
        mapOf(
            "requests" to requests.map { withoutClient(it as JsonElement) },
            "left" to boxes.keys.flatMap { box(it).items().map { item -> item.url } }.sorted(),
        )
    }

    private fun withoutClient(request: JsonElement): JsonElement {
        val r = request as? JsonObject ?: return request
        val body = r["body"] as? JsonObject ?: return request
        return JsonObject(r + ("body" to JsonObject(body - "source_client")))
    }
}
