package eu.chezmaurice.maurice.chat

import eu.chezmaurice.maurice.chat.ThreadScroll.Move
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The thread's scroll after a question is sent (iOS ChatView, 21 November
 * 2026): pinned at the top, the reply under it, the follow given back by the
 * member's hand only.
 */
class ThreadScrollTest {
    // A viewport of 1000 px whose top edge is at 0, 10 px of air, 20 of padding.
    private val viewport = 1000f
    private fun scroll() = ThreadScroll(air = 10f, bottomPadding = 20f)

    /** A conversation "c" open on its end, with a few messages. */
    private fun open(): ThreadScroll = scroll().also {
        it.opened("c", created = false)
        assertEquals(Move.ToEnd(animated = false), it.mounted("c", count = 4, lastIsOwn = false))
    }

    /** Sends a question in "c" and lets it land. */
    private fun ThreadScroll.send(old: Int = 4): Move {
        sent()
        return messagesChanged("c", old, old + 1, lastIsOwn = true)
    }

    // 1. ------------------------------------------------------------------

    @Test fun `a question sent is pinned and the follow stops`() {
        val s = open()
        assertEquals(Move.Pin, s.send())
        assertTrue(s.holding)
        assertTrue(s.pinnedIn("c"))
        assertFalse(s.follows)
    }

    @Test fun `every turn pins, not only the first`() {
        val s = open()
        assertEquals(Move.Pin, s.send(old = 4))
        s.messagesChanged("c", 5, 6, lastIsOwn = false) // the reply
        assertEquals(Move.Pin, s.send(old = 6))
    }

    @Test fun `a question of the member's from another device is pinned too`() {
        val s = open()
        assertEquals(Move.Pin, s.messagesChanged("c", 4, 5, lastIsOwn = true))
    }

    @Test fun `a post to a room's people is pinned like a question`() {
        // The composer arms the pin for 💬 as for ➤: same path.
        val s = open()
        assertEquals(Move.Pin, s.send())
    }

    @Test fun `the pin line is the top of a short question with air above`() {
        assertEquals(490f, scroll().pinLine(questionTop = 500f, questionHeight = 80f, viewport = viewport))
    }

    // 2. ------------------------------------------------------------------

    @Test fun `the room under a pinned turn gives way point for point and is gone at the fold`() {
        val s = open(); s.send()
        // Question pinned at the top (pin line 0), the reply still empty: text ends at 100.
        s.measureTurn(pinLine = 0f, textEnd = 100f, top = 0f, bottom = viewport)
        assertEquals(880f, s.tail("c", viewport))
        s.measureTurn(pinLine = 0f, textEnd = 400f, top = 0f, bottom = viewport)
        assertEquals(580f, s.tail("c", viewport))
        s.measureTurn(pinLine = 0f, textEnd = 980f, top = 0f, bottom = viewport)
        assertEquals(0f, s.tail("c", viewport))
        // Under the fold: the end is no longer laid out; the turn is at least the viewport.
        s.measureTurn(pinLine = 0f, textEnd = null, top = 0f, bottom = viewport)
        assertEquals(0f, s.tail("c", viewport))
    }

    @Test fun `the room does not depend on where the thread is scrolled`() {
        val s = open(); s.send()
        s.measureTurn(pinLine = 300f, textEnd = 700f, top = 0f, bottom = viewport)
        assertEquals(580f, s.tail("c", viewport))
    }

    @Test fun `no room in a conversation without a pin`() {
        val s = open()
        assertEquals(0f, s.tail("c", viewport))
        s.send()
        assertEquals(0f, s.tail("other", viewport))
    }

    @Test fun `while the reply writes itself nothing moves`() {
        val s = open(); s.send()
        repeat(20) { i ->
            val end = 100f + i * 100f
            s.measureTurn(pinLine = 0f, textEnd = end.takeIf { it < viewport }, top = 0f, bottom = viewport)
            s.bottomSeen(atBottom = end < viewport, viewport = viewport)
            assertFalse(s.follows, "followed at $end")
        }
    }

    // 3. ------------------------------------------------------------------

    @Test fun `scrolling to the end of a text under the fold gives the follow back`() {
        val s = open(); s.send()
        s.measureTurn(pinLine = 0f, textEnd = null, top = 0f, bottom = viewport) // text under the fold
        s.bottomSeen(atBottom = false, viewport = viewport)
        s.handChanged(true, viewport)
        s.measureTurn(pinLine = -400f, textEnd = 950f, top = 0f, bottom = viewport)
        s.bottomSeen(atBottom = true, viewport = viewport)
        assertFalse(s.holding)
        assertTrue(s.following)
        s.handChanged(false, viewport)
        assertTrue(s.follows)
    }

    @Test fun `a scroll that comes to rest on the end of a long text gives the follow back`() {
        val s = open(); s.send()
        s.measureTurn(pinLine = 0f, textEnd = null, top = 0f, bottom = viewport)
        s.handChanged(true, viewport)
        s.bottomSeen(atBottom = true, viewport = viewport)
        // Released as the end came into view under the hand.
        assertFalse(s.holding)
    }

    @Test fun `touching the thread while the reply fits under the question gives nothing back`() {
        val s = open(); s.send()
        s.measureTurn(pinLine = 0f, textEnd = 300f, top = 0f, bottom = viewport)
        s.bottomSeen(atBottom = true, viewport = viewport)
        s.handChanged(true, viewport)
        s.bottomSeen(atBottom = true, viewport = viewport)
        s.handChanged(false, viewport)
        assertTrue(s.holding)
        assertFalse(s.follows)
    }

    @Test fun `scrolling up stops the follow again`() {
        val s = open()
        assertTrue(s.follows)
        s.handChanged(true, viewport)
        s.scrolledUp()
        s.bottomSeen(atBottom = false, viewport = viewport)
        s.handChanged(false, viewport)
        assertFalse(s.follows)
        // And back at the end by hand: following again.
        s.handChanged(true, viewport)
        s.bottomSeen(atBottom = true, viewport = viewport)
        s.handChanged(false, viewport)
        assertTrue(s.follows)
    }

    @Test fun `the thread does not follow while the hand is on it`() {
        val s = open()
        s.handChanged(true, viewport)
        assertFalse(s.follows)
    }

    // 4. ------------------------------------------------------------------

    @Test fun `the end of the reply does not jump for one who was not following`() {
        val s = open(); s.send()
        assertEquals(Move.None, s.messagesChanged("c", 5, 6, lastIsOwn = false))
        assertEquals(Move.None, s.turnEnded())
    }

    @Test fun `the end of the reply keeps the end in view for one who was following`() {
        val s = open()
        assertEquals(Move.ToEnd(animated = true), s.messagesChanged("c", 4, 5, lastIsOwn = false))
        assertEquals(Move.ToEnd(animated = true), s.turnEnded())
    }

    @Test fun `someone else's message does not move a reader who scrolled up`() {
        val s = open()
        s.handChanged(true, viewport); s.scrolledUp(); s.handChanged(false, viewport)
        assertEquals(Move.None, s.messagesChanged("c", 4, 5, lastIsOwn = false))
    }

    // 5. ------------------------------------------------------------------

    @Test fun `of a long question only the last third of the screen stays on top`() {
        val s = scroll()
        // 900 px of question at 200: its last 333 px stay, air above.
        val line = s.pinLine(questionTop = 200f, questionHeight = 900f, viewport = viewport)
        assertEquals(200f + 900f - viewport / 3 - 10f, line)
        // The reply has the rest of the screen: the room is measured from that line.
        s.opened("c", created = false); s.mounted("c", 1, lastIsOwn = true)
        s.measureTurn(pinLine = line, textEnd = 1100f, top = 0f, bottom = viewport + 2000f)
        assertEquals(viewport - 20f - (1100f - line), s.tail("c", viewport))
    }

    // 6. ------------------------------------------------------------------

    @Test fun `the down arrow shows whenever the end is out of view, in history too`() {
        val s = open()
        s.bottomSeen(atBottom = false, viewport = viewport)
        assertTrue(s.showsScrollToEnd)
        s.bottomSeen(atBottom = true, viewport = viewport)
        assertFalse(s.showsScrollToEnd)
    }

    @Test fun `the down arrow goes to the end and follows from there`() {
        val s = open(); s.send()
        s.measureTurn(pinLine = 0f, textEnd = null, top = 0f, bottom = viewport)
        s.bottomSeen(atBottom = false, viewport = viewport)
        assertEquals(Move.ToEnd(animated = true), s.scrollToEndTapped())
        assertFalse(s.holding)
        assertTrue(s.follows)
    }

    // 7. ------------------------------------------------------------------

    @Test fun `opening a conversation lands on its end, following, no pin`() {
        val s = open(); s.send()
        s.opened("d", created = false)
        assertFalse(s.pinnedIn("d"))
        assertFalse(s.holding)
        assertEquals(Move.ToEnd(animated = false), s.mounted("d", count = 7, lastIsOwn = true))
        assertTrue(s.follows)
    }

    @Test fun `a conversation holding only the member's question pins it`() {
        val s = scroll()
        s.opened("d", created = false)
        assertEquals(Move.Pin, s.mounted("d", count = 1, lastIsOwn = true))
    }

    @Test fun `a new conversation's first question stays pinned once the conversation is created`() {
        val s = scroll()
        s.opened(null, created = false) // a blank page
        s.sent()
        assertEquals(Move.Pin, s.mounted(null, count = 1, lastIsOwn = true))
        s.opened("new", created = true)
        assertTrue(s.pinnedIn("new"))
        assertTrue(s.holding)
    }

    @Test fun `leaving a blank page for another conversation drops the pin`() {
        val s = scroll()
        s.opened(null, created = false)
        s.sent()
        s.mounted(null, count = 1, lastIsOwn = true)
        s.opened("d", created = false)
        assertFalse(s.pinnedIn("d"))
        assertTrue(s.follows)
    }

    @Test fun `a thread reloaded in place snaps to its end on its first population`() {
        val s = scroll()
        s.opened("c", created = false)
        assertEquals(Move.ToEnd(animated = true), s.messagesChanged("c", 0, 12, lastIsOwn = true))
    }

    // Traps --------------------------------------------------------------

    @Test fun `the end coming into view by itself does not give the follow back`() {
        // The streamed row gives way to the final message: the end of a text
        // under the fold comes into view without the member doing anything.
        val s = open(); s.send()
        s.measureTurn(pinLine = 0f, textEnd = null, top = 0f, bottom = viewport)
        s.bottomSeen(atBottom = false, viewport = viewport)
        s.bottomSeen(atBottom = true, viewport = viewport)
        assertTrue(s.holding)
        assertFalse(s.follows)
        assertEquals(Move.None, s.turnEnded())
    }

    @Test fun `mounting clears the first-load snap, so the next message does not jump`() {
        val s = scroll()
        s.opened("c", created = false)
        s.mounted("c", count = 4, lastIsOwn = false)
        s.handChanged(true, viewport); s.scrolledUp(); s.handChanged(false, viewport)
        // Someone else's message: the reader scrolled up stays where they are.
        assertEquals(Move.None, s.messagesChanged("c", 4, 5, lastIsOwn = false))
    }

    @Test fun `a pin is not put back under a hand that moved since`() {
        val s = open(); s.send()
        assertFalse(s.scrolledSincePin)
        s.handChanged(true, viewport)
        assertTrue(s.scrolledSincePin)
    }
}
