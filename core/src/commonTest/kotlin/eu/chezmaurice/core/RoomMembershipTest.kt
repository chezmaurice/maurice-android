package eu.chezmaurice.core

import eu.chezmaurice.core.chat.LiveSocket
import eu.chezmaurice.core.model.Participant
import eu.chezmaurice.core.model.canRemoveOthers
import io.ktor.websocket.CloseReason
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Rooms on a server of 26 November 2026: who may take someone out, and a socket shut for good. */
class RoomMembershipTest {
    private val room = listOf(
        Participant(memberId = "u1", role = "owner", displayName = "Camille"),
        Participant(memberId = "u2", role = "member", displayName = "Nadia"),
    )

    @Test fun theOneWhoOpenedTheRoomOrTheAdminMayTakeSomeoneOut() {
        assertTrue(canRemoveOthers(room, "u1", isAdmin = false), "the owner")
        assertFalse(canRemoveOthers(room, "u2", isAdmin = false), "another participant: the server would answer 403 not_owner")
        assertTrue(canRemoveOthers(room, "u2", isAdmin = true), "the household's admin")
    }

    @Test fun aSocketShutForGoodIsNotTriedAgain() {
        assertTrue(LiveSocket.isShut(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "left")))
        assertFalse(LiveSocket.isShut(CloseReason(CloseReason.Codes.GOING_AWAY, "restart")))
        assertFalse(LiveSocket.isShut(null))
        assertTrue(LiveSocket.isRefused(Exception("Handshake exception, expected status code 101 but was 403")))
        assertTrue(LiveSocket.isRefused(RuntimeException("wrapped", Exception("expected status code 101 but was 403"))))
        assertFalse(LiveSocket.isRefused(Exception("Handshake exception, expected status code 101 but was 502")))
        assertFalse(LiveSocket.isRefused(Exception("Connection refused")))
    }
}
