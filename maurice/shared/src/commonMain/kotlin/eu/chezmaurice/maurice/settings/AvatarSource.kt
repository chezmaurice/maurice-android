package eu.chezmaurice.maurice.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.chezmaurice.maurice.platform.normalizeToJpeg
import eu.chezmaurice.maurice.platform.rememberCamera
import eu.chezmaurice.maurice.platform.rememberPhotoPicker
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The member's photo, tapped: *Take Photo* or *Choose from Library*, as the
 * iOS confirmation dialog asks. The camera is the system's (the composer's
 * `rememberCamera`, through the app's own FileProvider); where there is none
 * — the desktop, a device without a camera — the tap opens the picker (a file
 * dialog on the desktop) straight away. [onPhoto] gets the photo upright and
 * shrunk, as JPEG, for the crop sheet.
 */
@Composable
fun AvatarSource(
    enabled: Boolean,
    onPhoto: (ByteArray) -> Unit,
    onDenied: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val theme = LocalMauriceTheme.current
    val scope = rememberCoroutineScope()
    fun ready(bytes: ByteArray) {
        scope.launch { withContext(Dispatchers.Default) { normalizeToJpeg(bytes) }?.let(onPhoto) }
    }
    val pick = rememberPhotoPicker(1) { picked -> picked.firstOrNull()?.let(::ready) }
    val camera = rememberCamera(onDenied = onDenied) { shot -> ready(shot) }
    var asking by remember { mutableStateOf(false) }
    Box(modifier.clickable(enabled = enabled) { if (camera == null) pick() else asking = true }, contentAlignment = Alignment.Center) {
        content()
        DropdownMenu(asking, onDismissRequest = { asking = false }, containerColor = theme.surface) {
            DropdownMenuItem(text = { Text(L("settings.avatar.take_photo"), color = theme.ink) }, onClick = { asking = false; camera?.invoke() })
            DropdownMenuItem(text = { Text(L("settings.avatar.choose_photo"), color = theme.ink) }, onClick = { asking = false; pick() })
        }
    }
}
