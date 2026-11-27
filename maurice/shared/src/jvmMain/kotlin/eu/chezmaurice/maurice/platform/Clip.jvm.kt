package eu.chezmaurice.maurice.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalComposeUiApi::class)
actual fun plainTextClip(text: String): ClipEntry = ClipEntry(StringSelection(text))

/** The AWT clipboard: pixels (sent as PNG), or a copied image file read from disk. */
@Composable
actual fun rememberImageClipboard(): ImageClipboard = remember { AwtImageClipboard }

private object AwtImageClipboard : ImageClipboard {
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "heic", "heif", "tif", "tiff")

    private fun clipboard() = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull()

    private fun imageFile(): File? = runCatching {
        val cb = clipboard() ?: return null
        if (!cb.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) return null
        (cb.getData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<File>()
            ?.firstOrNull { it.isFile && it.extension.lowercase() in imageExtensions }
    }.getOrNull()

    override fun hasImage(): Boolean = runCatching {
        clipboard()?.isDataFlavorAvailable(DataFlavor.imageFlavor) == true || imageFile() != null
    }.getOrDefault(false)

    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val cb = clipboard() ?: return@runCatching null
            if (cb.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                (cb.getData(DataFlavor.imageFlavor) as? Image)?.let { png(it) }
            } else imageFile()?.readBytes()
        }.getOrNull()
    }

    private fun png(image: Image): ByteArray? {
        val w = image.getWidth(null)
        val h = image.getHeight(null)
        if (w <= 0 || h <= 0) return null
        val buffered = image as? BufferedImage ?: BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { b ->
            b.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        }
        return ByteArrayOutputStream().use { out -> ImageIO.write(buffered, "png", out); out.toByteArray() }
    }
}
