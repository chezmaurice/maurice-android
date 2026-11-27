package eu.chezmaurice.maurice.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import eu.chezmaurice.core.media.PhotoSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
actual fun rememberPhotoPicker(max: Int, onPicked: (List<ByteArray>) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return {
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                val dialog = FileDialog(null as Frame?, "", FileDialog.LOAD).apply {
                    isMultipleMode = true
                    setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp") }
                    isVisible = true
                }
                dialog.files.orEmpty().toList()
            }
            val bytes = files.take(max).mapNotNull { f: File -> runCatching { f.readBytes() }.getOrNull() }
            if (bytes.isNotEmpty()) onPicked(bytes)
        }
    }
}

actual fun normalizeToJpeg(bytes: ByteArray): ByteArray? = runCatching {
    val image = Image.makeFromEncoded(bytes)
    val (w, h) = PhotoSize.target(image.width, image.height) ?: (image.width to image.height)
    val surface = Surface.makeRasterN32Premul(w, h)
    surface.canvas.drawImageRect(image, Rect.makeWH(w.toFloat(), h.toFloat()))
    surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, PhotoSize.JPEG_QUALITY)!!.bytes
}.getOrNull()
