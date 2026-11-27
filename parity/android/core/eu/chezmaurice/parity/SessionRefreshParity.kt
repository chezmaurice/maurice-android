package eu.chezmaurice.parity

import eu.chezmaurice.core.session.MauriceInvitation
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.store.MemoryKeyValueStore
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

class SessionRefreshParity {
    @Test fun refresh() = Parity.check("maurice", "session-refresh") { c ->
        val before = c.obj("before")
        fun b(k: String) = (before?.get(k) as? JsonPrimitive)?.content
        val session = Session(SessionSupport.store(color = b("color"), icon = b("icon"), key = b("key")), "maurice")
        session.refreshHouseholds()
        mapOf(
            "household" to SessionSupport.household(session),
            "households" to session.state.value.households.size,
            "signedIn" to SessionSupport.signedIn(session),
            "requests" to SessionSupport.distinctRequests(),
        )
    }

    @Test fun receive() = Parity.check("maurice", "session-receive") { c ->
        val session = if (c.bool("paired") == true) MauriceFixture.session() else Session(MemoryKeyValueStore(), "maurice")
        val hid = session.state.value.currentId
        val invitation = MauriceInvitation.parse(SessionSupport.sub(c.string("link").orEmpty())) ?: return@check mapOf("invitation" to null)
        session.receive(invitation)
        mapOf(
            "household" to SessionSupport.household(session),
            "households" to session.state.value.households.size,
            "activeMember" to session.state.value.current?.activeMemberId,
            "u1TokenKept" to (hid?.let { session.hasToken(it, "u1") } ?: false),
            "requests" to SessionSupport.distinctRequests(),
        )
    }
}
