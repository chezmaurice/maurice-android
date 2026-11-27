package eu.chezmaurice.carnet.platform

import androidx.compose.runtime.Composable
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Windows and Linux have no share sheet an app can count on: the card's
 * text (title, words, link) goes to the clipboard, to paste where the member
 * wants. The poster stays out.
 */
@Composable
actual fun rememberShareCard(): (ByteArray?, String?, String, String) -> Unit = { _, _, _, text ->
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}
