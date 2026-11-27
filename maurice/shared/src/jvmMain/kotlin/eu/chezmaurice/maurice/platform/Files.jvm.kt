package eu.chezmaurice.maurice.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import eu.chezmaurice.core.settings.Upload
import io.ktor.utils.io.jvm.javaio.copyTo
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

@Composable
actual fun rememberFilePicker(multiple: Boolean, mimeTypes: List<String>, onPicked: (List<Upload>) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return {
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                FileDialog(null as Frame?, "", FileDialog.LOAD).apply { isMultipleMode = multiple; isVisible = true }.files.orEmpty().toList()
            }
            if (files.isNotEmpty()) onPicked(files.map { f: File -> Upload(f.name, f.length()) { f.inputStream().toByteReadChannel() } })
        }
    }
}

@Composable
actual fun rememberFileSaver(mime: String, onTarget: (FileTarget?) -> Unit): (suggestedName: String) -> Unit {
    val scope = rememberCoroutineScope()
    return { name ->
        scope.launch {
            val dialog = withContext(Dispatchers.IO) { FileDialog(null as Frame?, "", FileDialog.SAVE).apply { file = name; isVisible = true } }
            val chosen = dialog.file?.let { File(dialog.directory, it) }
            onTarget(chosen?.let { f -> FileTarget { content -> withContext(Dispatchers.IO) { f.outputStream().use { content.copyTo(it) } } } })
        }
    }
}

actual fun qrMatrix(text: String): List<BooleanArray>? = runCatching {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
    List(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
}.getOrNull()

actual fun encodeJpeg(bitmap: ImageBitmap, quality: Int): ByteArray? = runCatching {
    Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.JPEG, quality)!!.bytes
}.getOrNull()

@Composable
actual fun rememberShareText(): (String) -> Unit = { text ->
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}
