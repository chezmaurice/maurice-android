package eu.chezmaurice.carnet.platform

import androidx.compose.runtime.Composable

/**
 * Hand an entry's card on (the poster's bytes and type, if any; its file
 * name; the text): Android's share sheet, image and text together; on the
 * desktop, which has no share sheet, the text copied to the clipboard.
 */
@Composable
expect fun rememberShareCard(): (image: ByteArray?, mime: String?, fileName: String, text: String) -> Unit
