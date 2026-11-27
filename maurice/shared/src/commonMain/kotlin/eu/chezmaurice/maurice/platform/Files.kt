package eu.chezmaurice.maurice.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import eu.chezmaurice.core.settings.Upload
import io.ktor.utils.io.ByteReadChannel

/**
 * Pick documents the platform's way — the system document picker on
 * Android, a file dialog on desktop — each read as a stream. [mimeTypes]
 * narrows the Android picker (empty for anything). Returns the function that opens it.
 */
@Composable
expect fun rememberFilePicker(multiple: Boolean, mimeTypes: List<String>, onPicked: (List<Upload>) -> Unit): () -> Unit

/** Where a download goes, once the member chose it. */
fun interface FileTarget {
    suspend fun write(content: ByteReadChannel)
}

/** Ask where to save a file named [suggestedName]; null when the member cancelled. */
@Composable
expect fun rememberFileSaver(mime: String, onTarget: (FileTarget?) -> Unit): (suggestedName: String) -> Unit

/** The modules of a QR code for [text], dark = true; null if it cannot be encoded. */
expect fun qrMatrix(text: String): List<BooleanArray>?

/** A drawn image as JPEG bytes. */
expect fun encodeJpeg(bitmap: ImageBitmap, quality: Int = 90): ByteArray?

/** Hand a link to the platform's share sheet (desktop: the clipboard). */
@Composable
expect fun rememberShareText(): (String) -> Unit
