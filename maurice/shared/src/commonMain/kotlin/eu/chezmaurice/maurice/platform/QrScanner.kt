package eu.chezmaurice.maurice.platform

import androidx.compose.runtime.Composable

/** Whether this platform can scan a QR code (a phone's camera); the desktop pastes the link. */
expect val canScanQr: Boolean

/** Full screen: the camera, until a QR code is read ([onResult] with its text) or [onCancel]. */
@Composable
expect fun QrScanner(onResult: (String) -> Unit, onCancel: () -> Unit)
