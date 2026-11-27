package eu.chezmaurice.maurice.chat

import android.view.KeyCharacterMap
import androidx.compose.ui.input.key.KeyEvent

/**
 * Most on-screen keyboards type a line break through the input method and send
 * no key at all; the few that do send a key mark it as theirs, or come from
 * the virtual keyboard device. Anything else is a physical keyboard.
 */
internal actual fun KeyEvent.isFromHardwareKeyboard(): Boolean {
    val native = nativeKeyEvent
    if (native.flags and android.view.KeyEvent.FLAG_SOFT_KEYBOARD != 0) return false
    if (native.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) return false
    return native.device?.isVirtual != true
}
