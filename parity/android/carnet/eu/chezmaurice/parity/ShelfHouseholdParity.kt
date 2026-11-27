package eu.chezmaurice.parity

import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.session.HouseholdMove
import eu.chezmaurice.core.carnet.CarnetEntry
import eu.chezmaurice.core.session.formatInviteCode
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.test.Test

/** Carnet's ways into a household and its following one that moved; `ShelfHouseholdParity.swift`. */
class ShelfHouseholdParity {
    @Test fun invitation() = Parity.check("carnet", "shelf-invitation") { c ->
        // The code as the member picker shows it; the parsed link is never shown again.
        CarnetEntry.invitation(c.string)?.let { mapOf("server" to it.serverUrl, "code" to formatInviteCode(it.code)) }
    }

    @Test fun address() = Parity.check("carnet", "shelf-address") { c -> CarnetEntry.address(c.string) }

    @Test fun moveRecord() = Parity.check("carnet", "shelf-move-record") { c ->
        val bytes = c.array("rdata")
        if (bytes != null) mapOf("txt" to HouseholdMove.decodeTxt(ByteArray(bytes.size) { bytes[it].jsonPrimitive.intOrNull!!.toByte() }))
        else mapOf("moved" to HouseholdMove.parse(c.string("record") ?: ""))
    }

    @Test fun moveProof() = Parity.check("carnet", "shelf-move-proof") { c ->
        val proves = HouseholdMove.proves(Parity.baseUrl, c.string("key") ?: "", ApiClient.defaultHttpClient())
        mapOf("proves" to proves, "asked" to ShelfObserve.requests(prefix = "/api/health").size)
    }

    @Test fun householdRefresh() = Parity.check("carnet", "shelf-household-refresh") { c ->
        val s = ShelfFixture.session()
        repeat(c.int("times") ?: 1) { s.refreshHouseholds() }
        val h = s.state.value.current!!
        mapOf(
            "name" to h.name, "color" to h.color, "icon" to h.icon, "key" to h.key,
            "serverURL" to h.serverUrl.replace(Parity.baseUrl, "{base}"), "signedIn" to (s.state.value.activeMember != null),
        )
    }
}
