package eu.chezmaurice.parity

import eu.chezmaurice.core.model.Household
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.model.Role
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.store.KeyValueStore
import eu.chezmaurice.core.store.MemoryKeyValueStore
import kotlinx.serialization.builtins.ListSerializer

/**
 * A device paired with the fake household, one member signed in — the
 * Android side of `MauriceFixture.swift`. Every call starts from a blank
 * device (a fresh in-memory store).
 */
object MauriceFixture {
    const val HOUSEHOLD_ID = "parity-household"
    const val HOUSEHOLD_NAME = "Parity"

    fun store(
        prefix: String = "maurice",
        member: String = "u1", token: String = "tok-u1",
        displayName: String = "Alice", role: Role? = Role.Standard,
    ): KeyValueStore = MemoryKeyValueStore().apply {
        val household = Household(
            id = HOUSEHOLD_ID, serverUrl = Parity.baseUrl, name = HOUSEHOLD_NAME,
            members = listOf(Member(id = member, displayName = displayName, role = role)),
            activeMemberId = member,
        )
        put("$prefix.households", MauriceJson.encodeToString(ListSerializer(Household.serializer()), listOf(household)))
        put("$prefix.currentHousehold", HOUSEHOLD_ID)
        putSecret("$prefix.token.$HOUSEHOLD_ID.$member", token)
    }

    /** A Session on the fake household, [member] signed in with [token]. */
    fun session(
        prefix: String = "maurice",
        member: String = "u1", token: String = "tok-u1",
        displayName: String = "Alice", role: Role? = Role.Standard,
    ): Session = Session(store(prefix, member, token, displayName, role), prefix)
}
