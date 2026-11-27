package eu.chezmaurice.maurice.chat

import kotlin.math.max

/**
 * Where the thread's scroll goes, decided apart from Compose (iOS ChatView,
 * 21 November 2026: `pinQuestion`, `releasePinIfUnderFold`, `TurnTailSpacer`).
 *
 * A question the member sends goes to the top of the thread and stays there:
 * the reply writes itself underneath, then on under the fold, instead of
 * pushing its own first lines off the top. The thread follows the reply again
 * only once the member's own hand has taken it to the end of a text that
 * reaches under the fold, or through the "↓" button.
 *
 * Lengths are in pixels, in the list's own coordinates: `top` and `bottom`
 * are the edges of the viewport as the list's layout reports them.
 */
internal class ThreadScroll(
    /** Air left above a pinned question. */
    private val air: Float,
    /** The list's padding under the end of the text. */
    private val bottomPadding: Float,
) {
    sealed interface Move {
        /** Leave the scroll where it is. */
        data object None : Move
        /** Bring the member's last question to the top of the viewport. */
        data object Pin : Move
        /** Bring the end of the text to the bottom of the viewport. */
        data class ToEnd(val animated: Boolean) : Move
    }

    /**
     * The thread follows its end as it grows. Only the member's hand turns it
     * off (a scroll up) and only reaching the end turns it back on — never
     * the content growing under the viewport.
     */
    var following = true
        private set

    /** A question is pinned and held: the reply is not followed. */
    var holding = false
        private set

    /** The end of the text is in view (or just under the edge). */
    var atBottom = true
        private set

    /** The member's hand is on the thread: dragging, flinging, a wheel. */
    var hand = false
        private set

    /**
     * Height from the pin line to the end of the text, as far as the layout
     * has shown it: exact when both are laid out, a lower bound otherwise.
     */
    var turnHeight = 0f
        private set

    /** Whether some conversation has a pinned question, and which one (a new one is null). */
    private var pinActive = false
    private var pinConversation: String? = null
    /** A question was just sent from this composer: pin it as it lands. */
    private var armed = false
    /** One snap to the end after a (re)load, whatever the scroll. */
    private var pendingToEnd = true
    /** The member has scrolled since the pin: it is not put back under them. */
    var scrolledSincePin = false
        private set

    /** The pinned question belongs to [conversation], the one on screen. */
    fun pinnedIn(conversation: String?): Boolean = pinActive && pinConversation == conversation

    /** The member sent a question (to Maurice or to the room's people). */
    fun sent() { armed = true }

    /**
     * The conversation on screen changed. Opening or switching one: to its
     * end, following, no pin. The conversation a first question has just
     * created ([created]) keeps that question pinned.
     */
    fun opened(to: String?, created: Boolean) {
        if (created && pinActive && pinConversation == null) {
            pinConversation = to
            return
        }
        pinActive = false
        pinConversation = null
        holding = false
        armed = false
        following = true
        pendingToEnd = true
        turnHeight = 0f
    }

    /**
     * The thread appears with its messages. Mounting is the first
     * population: nothing stays pending for the next message to trip on. A
     * conversation holding only the member's question (a new one) pins it.
     */
    fun mounted(conversation: String?, count: Int, lastIsOwn: Boolean): Move {
        pendingToEnd = false
        return if (lastIsOwn && (armed || count == 1)) pin(conversation) else Move.ToEnd(animated = false)
    }

    /** The number of messages went from [old] to [new]. */
    fun messagesChanged(conversation: String?, old: Int, new: Int, lastIsOwn: Boolean): Move = when {
        // Their own message, just sent: to the top, the reply under it.
        armed && lastIsOwn -> pin(conversation)
        // First population after a (re)load: always to the end.
        pendingToEnd -> { pendingToEnd = false; Move.ToEnd(animated = true) }
        // The same, sent from another of their devices.
        lastIsOwn && new > old -> pin(conversation)
        // Someone else's, or Maurice's: only for a reader who follows.
        following -> Move.ToEnd(animated = true)
        else -> Move.None
    }

    /** The reply is done and its message in place: no jump for one who was not following. */
    fun turnEnded(): Move = if (following) Move.ToEnd(animated = true) else Move.None

    private fun pin(conversation: String?): Move {
        armed = false
        pendingToEnd = false
        pinActive = true
        pinConversation = conversation
        holding = true
        scrolledSincePin = false
        following = false
        turnHeight = 0f
        return Move.Pin
    }

    /**
     * The line of the pinned question that goes to the top of the viewport:
     * a little air above a short question; of a long one, only its last
     * third of a screen.
     */
    fun pinLine(questionTop: Float, questionHeight: Float, viewport: Float): Float =
        questionTop + max(0f, questionHeight - viewport / 3) - air

    /**
     * What the layout shows of the pinned turn: the pin line ([pinLine], null
     * when the question is not laid out — above the viewport) and the end of
     * the text ([textEnd], null when it is under the viewport).
     */
    fun measureTurn(pinLine: Float?, textEnd: Float?, top: Float, bottom: Float) {
        turnHeight = when {
            pinLine != null && textEnd != null -> max(0f, textEnd - pinLine)
            // The end is under the fold: the turn reaches at least that far.
            pinLine != null -> max(turnHeight, bottom - pinLine)
            // The question is above the top: the turn starts at least that high.
            textEnd != null -> max(turnHeight, textEnd - top)
            else -> turnHeight
        }
    }

    /**
     * The room kept after the end of the text so that the pinned question
     * can sit at the top while the reply is short. It gives way point for
     * point as the reply grows, and is 0 once the text reaches the fold.
     */
    fun tail(conversation: String?, viewport: Float): Float =
        if (pinnedIn(conversation)) max(0f, viewport - bottomPadding - turnHeight) else 0f

    /** The pinned turn's text reaches under the fold. */
    private fun underFold(viewport: Float) = turnHeight > viewport - bottomPadding

    /**
     * The end of the text is or is not in view. With a pinned question, the
     * end of a short reply is in view from the start, and that is no reason
     * to follow: only the member's hand taking the thread to the end of a
     * text that has gone under the fold does it — the end also comes into
     * view when the reply row gives way to the final message.
     */
    fun bottomSeen(atBottom: Boolean, viewport: Float) {
        this.atBottom = atBottom
        if (holding) {
            if (atBottom && hand) release(viewport)
        } else if (atBottom) {
            following = true
        }
    }

    /** The member's hand takes the thread, or the scroll it started comes to rest. */
    fun handChanged(active: Boolean, viewport: Float) {
        hand = active
        if (active) scrolledSincePin = true
        else if (holding && atBottom) release(viewport)
    }

    /** The member's hand moved the thread up, toward earlier messages. */
    fun scrolledUp() { following = false }

    /** "↓": to the end of the text, and follow from there. */
    fun scrollToEndTapped(): Move {
        holding = false
        following = true
        return Move.ToEnd(animated = true)
    }

    private fun release(viewport: Float) {
        if (!underFold(viewport)) return
        holding = false
        following = true
    }

    /** Whether the thread keeps its end in view as it grows, now. */
    val follows: Boolean get() = following && !holding && !hand

    /** The "↓" button: text under the fold, whatever the reason. */
    val showsScrollToEnd: Boolean get() = !atBottom
}
