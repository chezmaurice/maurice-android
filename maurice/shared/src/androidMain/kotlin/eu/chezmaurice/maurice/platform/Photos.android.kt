package eu.chezmaurice.maurice.platform

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import eu.chezmaurice.core.media.PhotoSize
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

@Composable
actual fun rememberPhotoPicker(max: Int, onPicked: (List<ByteArray>) -> Unit): () -> Unit {
    val context = LocalContext.current
    fun read(uris: List<android.net.Uri>) {
        val bytes = uris.take(max).mapNotNull { uri ->
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        }
        if (bytes.isNotEmpty()) onPicked(bytes)
    }
    val many = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxOf(2, max))) { read(it) }
    // One photo (an avatar): a tap picks it, no "Add" to confirm.
    val one = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) read(listOf(uri)) }
    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    return { if (max == 1) one.launch(request) else many.launch(request) }
}

actual fun normalizeToJpeg(bytes: ByteArray): ByteArray? = runCatching {
    // ImageDecoder applies the EXIF orientation itself, and decodes HEIC too.
    val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        PhotoSize.target(info.size.width, info.size.height)?.let { (w, h) -> decoder.setTargetSize(w, h) }
    }
    ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, PhotoSize.JPEG_QUALITY, out)
        bitmap.recycle()
        out.toByteArray()
    }
}.getOrNull()
