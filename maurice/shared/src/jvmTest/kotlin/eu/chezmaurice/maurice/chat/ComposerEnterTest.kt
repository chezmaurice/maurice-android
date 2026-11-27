package eu.chezmaurice.maurice.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/** Enter in the composer. */
class ComposerEnterTest {
    private fun enter(
        hardware: Boolean = true,
        composing: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
        room: Boolean = false,
        canSend: Boolean = true,
        canAskMaurice: Boolean = true,
    ) = enterAction(hardware, composing, ctrl, shift, alt, room, canSend, canAskMaurice)

    @Test fun `a hardware Enter sends to Maurice`() = assertEquals(EnterAction.AskMaurice, enter())

    @Test fun `in a room it goes to the people, the default button`() =
        assertEquals(EnterAction.PostToRoom, enter(room = true))

    @Test fun `Ctrl-Enter is still Maurice's, in a room too`() {
        assertEquals(EnterAction.AskMaurice, enter(ctrl = true))
        assertEquals(EnterAction.AskMaurice, enter(ctrl = true, room = true))
    }

    @Test fun `Shift- and Alt-Enter break the line`() {
        assertEquals(EnterAction.NewLine, enter(shift = true))
        assertEquals(EnterAction.NewLine, enter(alt = true, room = true))
    }

    @Test fun `an on-screen keyboard's Enter is left to the field`() {
        assertEquals(EnterAction.Pass, enter(hardware = false))
        assertEquals(EnterAction.Pass, enter(hardware = false, ctrl = true))
    }

    @Test fun `Enter confirming a composition never sends`() {
        assertEquals(EnterAction.Pass, enter(composing = true))
        assertEquals(EnterAction.Pass, enter(composing = true, ctrl = true, room = true))
    }

    @Test fun `nothing to send, or Maurice still answering, and nothing happens`() {
        assertEquals(EnterAction.Swallow, enter(canSend = false))
        assertEquals(EnterAction.Swallow, enter(canSend = false, room = true))
        assertEquals(EnterAction.Swallow, enter(canAskMaurice = false))
        assertEquals(EnterAction.Swallow, enter(canAskMaurice = false, ctrl = true))
        // The room's people can be written to while Maurice answers.
        assertEquals(EnterAction.PostToRoom, enter(canAskMaurice = false, room = true))
    }
}
