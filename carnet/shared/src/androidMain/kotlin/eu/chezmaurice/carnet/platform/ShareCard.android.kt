package eu.chezmaurice.carnet.platform

import android.content.ClipData
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/**
 * The share sheet with the poster and the card's text. The poster is written
 * to the cache (`cards/`, emptied each time) and lent through the app's
 * FileProvider (`<applicationId>.cards`, declared by the launcher); without
 * one, the text goes alone.
 */
@Composable
actual fun rememberShareCard(): (ByteArray?, String?, String, String) -> Unit {
    val context = LocalContext.current
    return { image, mime, fileName, text ->
        val send = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, text)
            if (image != null) {
                val dir = File(context.cacheDir, "cards").apply { deleteRecursively(); mkdirs() }
                val file = File(dir, fileName).apply { writeBytes(image) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.cards", file)
                type = mime ?: "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(null, uri) // the sheet's preview
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
