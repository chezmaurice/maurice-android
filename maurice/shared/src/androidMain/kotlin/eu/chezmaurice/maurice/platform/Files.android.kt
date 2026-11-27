package eu.chezmaurice.maurice.platform

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import eu.chezmaurice.core.settings.Upload
import io.ktor.utils.io.jvm.javaio.copyTo
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import java.io.ByteArrayOutputStream

@Composable
actual fun rememberFilePicker(multiple: Boolean, mimeTypes: List<String>, onPicked: (List<Upload>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun upload(uri: Uri): Upload {
        var name = uri.lastPathSegment ?: "file"
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        return Upload(name, size) { resolver.openInputStream(uri)!!.toByteReadChannel() }
    }
    val many = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onPicked(uris.map(::upload))
    }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(listOf(upload(uri)))
    }
    val types = mimeTypes.ifEmpty { listOf("*/*") }.toTypedArray()
    return { if (multiple) many.launch(types) else one.launch(types) }
}

@Composable
actual fun rememberFileSaver(mime: String, onTarget: (FileTarget?) -> Unit): (suggestedName: String) -> Unit {
    val resolver = LocalContext.current.contentResolver
    val callback by rememberUpdatedState(onTarget)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        callback(uri?.let { u -> FileTarget { content -> resolver.openOutputStream(u)!!.use { content.copyTo(it) } } })
    }
    return { name -> launcher.launch(name) }
}

actual fun qrMatrix(text: String): List<BooleanArray>? = runCatching {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
    List(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
}.getOrNull()

actual fun encodeJpeg(bitmap: ImageBitmap, quality: Int): ByteArray? = runCatching {
    ByteArrayOutputStream().use { out -> bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, quality, out); out.toByteArray() }
}.getOrNull()

@Composable
actual fun rememberShareText(): (String) -> Unit {
    val context = LocalContext.current
    return { text ->
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
