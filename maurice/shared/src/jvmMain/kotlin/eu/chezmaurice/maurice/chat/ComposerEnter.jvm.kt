package eu.chezmaurice.maurice.chat

import androidx.compose.ui.input.key.KeyEvent

/** On Windows and Linux, every key is a physical keyboard's. */
internal actual fun KeyEvent.isFromHardwareKeyboard(): Boolean = true
