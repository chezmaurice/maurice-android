package eu.chezmaurice.parity

import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.session.SharedPairings
import kotlin.test.Test

/** The pairings the Maurice app and Carnet share: joined the first time. */
class SessionSharedPairingsParity {
    @Test fun absorb() = Parity.check("maurice", "session-shared-pairings") { c ->
        fun pairings(key: String) = MauriceJson.decodeFromString(SharedPairings.serializer(), c.text(key))
        val joined = pairings("mine").absorb(pairings("theirs"))
        mapOf(
            "current" to joined.current,
            "households" to joined.households.map { h ->
                mapOf(
                    "server" to h.server, "key" to h.key, "name" to h.name,
                    "members" to h.members.map { mapOf("id" to it.id, "name" to it.displayName) },
                    "active" to h.active, "tokens" to h.tokens,
                )
            },
        )
    }
}
