package eu.chezmaurice.maurice.platform

import androidx.compose.runtime.Composable

/** The desktop has no camera to hand over to: photos come from files. */
@Composable
actual fun rememberCamera(onDenied: () -> Unit, onCaptured: (ByteArray) -> Unit): (() -> Unit)? = null
