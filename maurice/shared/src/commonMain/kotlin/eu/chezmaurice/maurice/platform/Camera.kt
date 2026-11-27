package eu.chezmaurice.maurice.platform

import androidx.compose.runtime.Composable

/**
 * The system camera, for a photo to send — `SystemCameraPicker` on iOS: the
 * platform's own camera app (landscape, flash, zoom, retake) through Android's
 * `ACTION_IMAGE_CAPTURE`, no Google service. Hands back the photo's bytes.
 * Null where there is no camera to hand over to (the desktop, a device
 * without one): the + menu then offers no camera. [onDenied] is told when the
 * member refuses the camera permission Android asks for first.
 */
@Composable
expect fun rememberCamera(onDenied: () -> Unit = {}, onCaptured: (ByteArray) -> Unit): (() -> Unit)?
