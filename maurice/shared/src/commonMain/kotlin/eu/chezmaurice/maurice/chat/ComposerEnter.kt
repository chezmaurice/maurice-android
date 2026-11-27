package eu.chezmaurice.maurice.chat

import androidx.compose.ui.input.key.KeyEvent

/** What an Enter press does in the chat composer. */
internal enum class EnterAction {
    /** Not ours: the field does what it always does (an on-screen keyboard's line break). */
    Pass,
    /** Ours, and nothing happens: nothing to send, or Maurice is still answering. */
    Swallow,
    /** A line break typed at the caret. */
    NewLine,
    /** ➤: the draft goes to Maurice. */
    AskMaurice,
    /** 💬: the draft goes to the room's people. */
    PostToRoom,
}

/**
 * Enter in the composer (16 November
 * 2026), as in every chat app on a computer:
 *
 * - a **hardware keyboard**'s Enter sends — to the room (💬) in a room, its
 *   default button, to Maurice (➤) otherwise; Shift- or Alt-Enter breaks the
 *   line; Ctrl-Enter is still ➤'s shortcut, in a room too;
 * - an **on-screen keyboard**'s Enter keeps breaking the line: there, a
 *   message leaves by its button;
 * - while an input method is **composing** (Japanese, Chinese, a dead key),
 *   Enter confirms the composition and is never a send;
 * - with nothing to send, or Maurice still answering (➤), it does nothing.
 *
 * Until then Enter broke the line everywhere and only Ctrl-Enter sent.
 */
internal fun enterAction(
    hardware: Boolean,
    composing: Boolean,
    ctrl: Boolean,
    shift: Boolean,
    alt: Boolean,
    room: Boolean,
    canSend: Boolean,
    canAskMaurice: Boolean,
): EnterAction {
    if (composing || !hardware) return EnterAction.Pass
    if (ctrl) return if (canSend && canAskMaurice) EnterAction.AskMaurice else EnterAction.Swallow
    if (shift || alt) return EnterAction.NewLine
    return when {
        !canSend -> EnterAction.Swallow
        room -> EnterAction.PostToRoom
        canAskMaurice -> EnterAction.AskMaurice
        else -> EnterAction.Swallow
    }
}

/** True when the key came from a physical keyboard, not from one drawn on the screen. */
internal expect fun KeyEvent.isFromHardwareKeyboard(): Boolean
