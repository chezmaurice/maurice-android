package eu.chezmaurice.maurice.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

actual val canScanQr: Boolean = false

@Composable
actual fun QrScanner(onResult: (String) -> Unit, onCancel: () -> Unit) {
    LaunchedEffect(Unit) { onCancel() }
}
