package eu.chezmaurice.core

import eu.chezmaurice.core.chat.RoomEvent
import eu.chezmaurice.core.chat.UserEvent
import eu.chezmaurice.core.chat.parseRoomEvent
import eu.chezmaurice.core.chat.parseUserEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SocketEventTest {
    @Test fun activity() = assertEquals(
        UserEvent.Activity("c9", "Toscane", "Camille", "On part à 8 h ?"),
        parseUserEvent("""{"type":"activity","conversationId":"c9","title":"Toscane","author":"Camille","preview":"On part à 8 h ?"}"""),
    )

    @Test fun mauriceOpenedOne() {
        assertIs<UserEvent.Opened>(
            parseUserEvent("""{"type":"conversation_opened","conversationId":"c3","title":"Un mot sur ta journée","author":"Maurice","preview":"…"}"""),
        )
    }

    @Test fun roomMessage() {
        val event = assertIs<RoomEvent.NewMessage>(parseRoomEvent(
            """{"type":"message","message":{"id":"m7","conversation_id":"c1","role":"user","content":"Salut","author_id":"u1","created_at":"2026-10-21 20:00:00"}}"""
        ))
        assertEquals("u1", event.message.authorId)
    }

    @Test fun summoned() = assertEquals(RoomEvent.Summoned("u1"), parseRoomEvent("""{"type":"summoned","by":"u1"}"""))

    @Test fun pingsAndUnknownsAreDropped() {
        assertNull(parseRoomEvent("""{"type":"ping"}"""))
        assertNull(parseUserEvent("""{"type":"ping"}"""))
        assertNull(parseUserEvent("""{"type":"something_new","conversationId":"c1"}"""))
    }
}
