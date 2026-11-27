package eu.chezmaurice.parity

import eu.chezmaurice.core.net.shownMessage
import eu.chezmaurice.core.settings.GuestInvite
import kotlin.test.Test

/** A friend from another household invited as a guest (accounts-guest-invite.json); see AccountsGuestInviteParity.swift. */
class AccountsGuestInviteParity {
    @Test fun guestInvite() = Parity.check("maurice", "accounts-guest-invite") { c ->
        val member = c.string("member").orEmpty()
        val api = MauriceFixture.session().api()!!
        val result = when (val outcome = GuestInvite.create(api, Parity.baseUrl, member)) {
            is GuestInvite.Outcome.Issued -> {
                val i = outcome.code.invitation(Parity.baseUrl)
                mapOf(
                    "kind" to "code", "code" to outcome.code.code, "expires_at" to outcome.code.expiresAt,
                    "invitation" to mapOf("server" to port(i.serverUrl), "code" to i.code,
                        "link" to port(eu.chezmaurice.core.session.MauriceInvitation.link(i.serverUrl, i.code))),
                )
            }
            GuestInvite.Outcome.NotAllowed -> mapOf("kind" to "not_allowed")
            // What Settings shows under the rows.
            is GuestInvite.Outcome.Failed -> mapOf("kind" to "failed", "message" to outcome.error.shownMessage())
        }
        val refused = buildMap {
            put("self", GuestInvite.isRefused(Parity.baseUrl, member))
            c.string("also")?.let { put("also", GuestInvite.isRefused(Parity.baseUrl, it)) }
        }
        mapOf("requests" to Parity.requests(), "result" to result, "refused" to refused)
    }

    /** The fake household's address, whatever port this run gave it. */
    private fun port(s: String) = s.replace(Parity.baseUrl, "<household>")
}
