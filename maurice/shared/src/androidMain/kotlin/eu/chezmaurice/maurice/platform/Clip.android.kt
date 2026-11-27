package eu.chezmaurice.maurice.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun plainTextClip(text: String): ClipEntry = ClipEntry(ClipData.newPlainText("Maurice", text))

/** The system clipboard: an item whose type is an image, read through its content URI. */
@Composable
actual fun rememberImageClipboard(): ImageClipboard {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        object : ImageClipboard {
            private val manager get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            override fun hasImage(): Boolean =
                runCatching { manager.primaryClipDescription?.hasMimeType("image/*") == true }.getOrDefault(false)

            override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
                runCatching {
                    val clip = manager.primaryClip ?: return@runCatching null
                    (0 until clip.itemCount).firstNotNullOfOrNull { i ->
                        val uri = clip.getItemAt(i).uri ?: return@firstNotNullOfOrNull null
                        val type = context.contentResolver.getType(uri) ?: clip.description.getMimeType(0)
                        if (type?.startsWith("image/") != true) null
                        else context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }
                }.getOrNull()
            }
        }
    }
}
