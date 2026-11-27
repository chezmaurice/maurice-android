package eu.chezmaurice.parity

import eu.chezmaurice.core.session.MauriceInvitation
import kotlin.test.Test

class SessionInvitationParity {
    @Test fun invitation() = Parity.check("maurice", "session-invitation") { c ->
        MauriceInvitation.parse(c.string)?.let { mapOf("server" to it.serverUrl, "code" to it.code, "link" to MauriceInvitation.link(it.serverUrl, it.code)) }
    }

    @Test fun invitationLink() = Parity.check("maurice", "session-invitation-link") { c ->
        MauriceInvitation.link(c.string("server").orEmpty(), c.string("code").orEmpty())
    }

    @Test fun address() = Parity.check("maurice", "session-address") { c ->
        MauriceInvitation.address(c.string)
    }
}
