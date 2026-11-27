package eu.chezmaurice.maurice.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ClipEntry

/** Plain text for the clipboard; each platform wraps it its own way. */
expect fun plainTextClip(text: String): ClipEntry

/** The clipboard as the composer's + menu reads it: an image to attach (`clipboardHasImage`). */
interface ImageClipboard {
    /**
     * Whether it holds an image — pixels, or a copied image file. Read when
     * the menu opens; it looks at what the clipboard says it holds, not at
     * its contents, so Android shows no "pasted from your clipboard" toast.
     */
    fun hasImage(): Boolean

    /** The image's bytes, in whatever format it comes; null if there is none any more. */
    suspend fun read(): ByteArray?
}

@Composable
expect fun rememberImageClipboard(): ImageClipboard
