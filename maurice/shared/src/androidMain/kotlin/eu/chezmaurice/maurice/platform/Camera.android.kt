package eu.chezmaurice.maurice.platform

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * The provider the camera writes through. A class of its own: Carnet declares
 * androidx's FileProvider for its cards, and two providers of one class clash
 * in the merged manifest.
 */
class CameraFileProvider : FileProvider()

@Composable
actual fun rememberCamera(onDenied: () -> Unit, onCaptured: (ByteArray) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val file = remember { File(context.cacheDir, "camera/capture.jpg") }
    // The provider is declared in this module's manifest, under the app's id.
    val uri = remember {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) null
        else runCatching { FileProvider.getUriForFile(context, context.packageName + ".camera", file) }.getOrNull()
    }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) runCatching { file.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let(onCaptured)
        file.delete()
    }
    fun open() {
        if (uri == null) return
        file.parentFile?.mkdirs()
        // No camera app at all (a stripped-down system): nothing happens.
        runCatching { take.launch(uri) }
    }
    // The app holds the CAMERA permission (for invitation QR codes): Android then
    // refuses ACTION_IMAGE_CAPTURE to it until the member has granted it.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) open() else onDenied() }
    if (uri == null) return null
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) open()
        else ask.launch(Manifest.permission.CAMERA)
    }
}
