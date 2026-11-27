package eu.chezmaurice.core

import eu.chezmaurice.core.chat.TurnEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurnEventTest {
    @Test fun textDelta() = assertEquals(TurnEvent.TextDelta("Bon"), TurnEvent.parse("""{"type":"text_delta","text":"Bon"}"""))

    @Test fun toolCallEnds() {
        val event = assertIs<TurnEvent.ToolCall>(TurnEvent.parse("""{"type":"tool_call","tool":"corpus__search","status":"end"}"""))
        assertEquals(false, event.started)
    }

    @Test fun errorReadsMessage() =
        assertEquals(TurnEvent.Error("budget"), TurnEvent.parse("""{"type":"error","message":"budget"}"""))

    @Test fun doneIsTerminal() = assertTrue(TurnEvent.parse("""{"type":"done","message_id":"m1"}""")!!.isTerminal)

    @Test fun resumeCarriesAnArrayOfBlocks() {
        val event = assertIs<TurnEvent.Resume>(TurnEvent.parse(
            """{"type":"resume","text":"so far","data":[{"tool":"web_search","data":{"card":"sources"}}],"tool":null,"usage":null,"started_at":"2026-10-21T08:12:03.123Z","finished":false}"""
        ))
        assertEquals("so far", event.text)
        assertEquals("web_search", event.data.single().tool)
    }

    @Test fun unknownAndBrokenLinesAreDropped() {
        assertNull(TurnEvent.parse("""{"type":"something_new","x":1}"""))
        assertNull(TurnEvent.parse("not json"))
        assertNull(TurnEvent.parse(""))
    }
}
