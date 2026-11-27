package eu.chezmaurice.maurice.platform

import androidx.compose.runtime.Composable

/**
 * Pick photos the platform's way — Android's photo picker (no permission,
 * present on GrapheneOS), a file dialog on desktop — and hand back their
 * bytes. Returns the function that opens the picker.
 */
@Composable
expect fun rememberPhotoPicker(max: Int, onPicked: (List<ByteArray>) -> Unit): () -> Unit

/** Decode any photo, turn it upright, shrink it to [eu.chezmaurice.core.media.PhotoSize], encode it as JPEG. */
expect fun normalizeToJpeg(bytes: ByteArray): ByteArray?
