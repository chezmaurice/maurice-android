package eu.chezmaurice.core.session

import eu.chezmaurice.core.model.Member
import kotlinx.serialization.Serializable

/**
 * The households this device is paired to, as the Maurice app and Carnet
 * both see them (`SharedPairings.swift`, 11 November 2026): joining a
 * household, signing in, signing out or forgetting one in either app is the
 * same in the other. Each app keeps its own copy in its own format (its local
 * household ids, its own token keys); this is what the two agree on,
 * households known by their address and their key.
 */
@Serializable
data class SharedPairings(
    val households: List<Household> = emptyList(),
    /** The address of the household on screen. */
    val current: String? = null,
    /** The phone's id, sent as `device_id` with every sign-in: the same in both apps (the smaller one wins). */
    val deviceId: String? = null,
) {
    @Serializable
    data class Household(
        val server: String,
        /** The household's identity key, once pinned (`/api/health`). */
        val key: String? = null,
        val name: String,
        val color: String? = null,
        val icon: String? = null,
        val members: List<Member> = emptyList(),
        val active: String? = null,
        /** Each signed-in member's session, by member id. */
        val tokens: Map<String, String> = emptyMap(),
    ) {
        /** The same household: the same key when both are known (it may have moved), else the same address. */
        fun same(server: String, key: String?): Boolean =
            if (key != null && this.key != null) key == this.key else server == this.server
    }

    fun index(server: String, key: String?): Int? =
        households.indexOfFirst { it.same(server, key) }.takeIf { it >= 0 }

    /**
     * Another app's pairings folded in, the first time an app joins: what this
     * one already holds wins, what only the other knows is added.
     */
    fun absorb(other: SharedPairings): SharedPairings {
        val mine = households.toMutableList()
        for (theirs in other.households) {
            val i = mine.indexOfFirst { it.same(theirs.server, theirs.key) }
            if (i < 0) { mine += theirs; continue }
            val h = mine[i]
            mine[i] = h.copy(
                members = h.members + theirs.members.filter { m -> h.members.none { it.id == m.id } },
                tokens = theirs.tokens + h.tokens,
                key = h.key ?: theirs.key,
                active = h.active ?: theirs.active,
            )
        }
        return SharedPairings(mine, current ?: other.current, listOfNotNull(deviceId, other.deviceId).minOrNull())
    }
}

/**
 * Where the two apps meet: on Android each app's provider, readable by the
 * other one only (a permission of signature level: both apps carry the same
 * key); on the desktop a file both read. [read] gives the latest of the two;
 * [write] stores and tells the other app.
 */
interface PairingsChannel {
    fun read(): SharedPairings?
    fun write(pairings: SharedPairings)
}
