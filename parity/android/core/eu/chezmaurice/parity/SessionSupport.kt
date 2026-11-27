package eu.chezmaurice.parity

import eu.chezmaurice.core.model.Household
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.model.Role
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.store.KeyValueStore
import eu.chezmaurice.core.store.MemoryKeyValueStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Helpers of the Session lot (invitations, pairing, sign-in, preferences,
 * consent, budget) — the Kotlin side of SessionSupport.swift.
 */
object SessionSupport {
    /** `{base}` → the fake household; `{localhost}` → the same, spelt otherwise. */
    fun sub(s: String) = s.replace("{base}", Parity.baseUrl).replace("{localhost}", "http://localhost:${Parity.port}")

    /** The reverse, so goldens do not depend on the port. */
    fun unsub(s: String) = s.replace(Parity.baseUrl, "{base}").replace("http://localhost:${Parity.port}", "{localhost}")

    /** The household the device is on, as the switcher shows it. */
    fun household(session: Session): Map<String, Any?>? {
        val h = session.state.value.current ?: return null
        return mapOf(
            "address" to unsub(h.serverUrl), "name" to unsub(h.name),
            "color" to h.color, "icon" to h.icon, "key" to h.key, "unread" to h.unread,
        )
    }

    /** Signed in as iOS's `hasActiveSession` means it: an active member whose token the device holds. */
    fun signedIn(session: Session): Boolean {
        val h = session.state.value.current ?: return false
        val id = h.activeMemberId ?: return false
        return session.hasToken(h.id, id)
    }

    /** The fake household's log, less the live socket's reconnections (`/api/me/ws`). */
    suspend fun log(): List<JsonObject> =
        ((Parity.requests() as? JsonArray) ?: JsonArray(emptyList())).map { it.jsonObject }
            .filter { (it["path"] as? JsonPrimitive)?.content != "/api/me/ws" }

    suspend fun requests(sorted: Boolean = false): List<JsonObject> {
        val log = log().map { JsonObject(it - "query") }
        if (!sorted) return log
        fun key(r: JsonObject) = listOf("method", "path", "auth").joinToString(" ") { k -> (r[k] as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content ?: "nil" }
        return log.sortedBy(::key)
    }

    /** The distinct requests, without bodies. */
    suspend fun distinctRequests(): List<String> = log().map { r ->
        fun s(k: String) = (r[k] as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content
        "${s("method")} ${s("path")} ${s("auth") ?: "-"}" + if ((r["unmatched"] as? JsonPrimitive)?.booleanOrNull == true) " unmatched" else ""
    }.toSet().sorted()

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    fun hexString(b: ByteArray): String = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /**
     * MauriceFixture.store, with what the household already holds ([color],
     * [icon], [key]) and, with [second], u2 signed in on this device earlier.
     */
    fun store(color: String? = null, icon: String? = null, key: String? = null, second: Boolean = false): KeyValueStore =
        MemoryKeyValueStore().apply {
            val members = listOf(Member(id = "u1", displayName = "Alice", role = Role.Standard)) +
                if (second) listOf(Member(id = "u2", displayName = "Bob", avatarColor = "#2c5aa0", hasPin = true, role = Role.Standard)) else emptyList()
            val household = Household(
                id = MauriceFixture.HOUSEHOLD_ID, serverUrl = Parity.baseUrl, name = MauriceFixture.HOUSEHOLD_NAME,
                color = color, icon = icon, key = key, members = members, activeMemberId = "u1",
            )
            put("maurice.households", MauriceJson.encodeToString(ListSerializer(Household.serializer()), listOf(household)))
            put("maurice.currentHousehold", MauriceFixture.HOUSEHOLD_ID)
            putSecret("maurice.token.${MauriceFixture.HOUSEHOLD_ID}.u1", "tok-u1")
            if (second) putSecret("maurice.token.${MauriceFixture.HOUSEHOLD_ID}.u2", "tok-u2")
        }
}
