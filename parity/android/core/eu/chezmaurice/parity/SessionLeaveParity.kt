package eu.chezmaurice.parity

import eu.chezmaurice.core.budget.BudgetStore
import eu.chezmaurice.core.consent.AiConsentStore
import eu.chezmaurice.core.session.Session
import kotlin.test.Test

class SessionLeaveParity {
    @Test fun leave() = Parity.check("maurice", "session-leave") { c ->
        val session = Session(SessionSupport.store(second = c.bool("second") == true), "maurice")
        val hid = MauriceFixture.HOUSEHOLD_ID
        when (c.string("action")) {
            "signOut" -> session.signOut()
            "forget" -> session.disconnect(hid)
        }
        mapOf(
            "requests" to SessionSupport.requests(sorted = true),
            "households" to session.state.value.households.size,
            "activeMember" to session.state.value.current?.activeMemberId,
            "u1TokenKept" to session.hasToken(hid, "u1"),
            "u2TokenKept" to session.hasToken(hid, "u2"),
        )
    }

    @Test fun rejected() = Parity.check("maurice", "session-rejected") { c ->
        val session = MauriceFixture.session()
        val hid = MauriceFixture.HOUSEHOLD_ID
        when (c.string("via")) {
            "prefs" -> session.loadServerPrefs("en")
            "budget" -> BudgetStore(session.api()!!, session.store, "${Parity.baseUrl}|u1").refresh()
            "consent" -> AiConsentStore(session.api()!!).refresh()
        }
        val state = session.state.value
        mapOf(
            "signedIn" to SessionSupport.signedIn(session),
            "activeMember" to state.current?.activeMemberId,
            // The picker says why when the household it shows is the one whose session ended.
            "sessionEnded" to (state.endedHouseholdId != null && state.endedHouseholdId == state.currentId),
            "u1TokenKept" to session.hasToken(hid, "u1"),
            "households" to state.households.size,
        )
    }
}
