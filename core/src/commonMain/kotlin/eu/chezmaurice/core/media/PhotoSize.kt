package eu.chezmaurice.core.media

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** What a photo may weigh once sent — `ChatView.swift`, `normalizeToJPEG`. */
object PhotoSize {
    const val MAX_LONG_EDGE = 1568
    const val MAX_VISUAL_TOKENS = 1568
    private const val PATCH = 28
    const val JPEG_QUALITY = 85
    const val MAX_PHOTOS = 10

    fun visualTokens(width: Int, height: Int): Int =
        ceil(width / PATCH.toDouble()).toInt() * ceil(height / PATCH.toDouble()).toInt()

    /**
     * The size to render at so both the edge limit and the token grid fit, or
     * null when the photo is small enough already. Each side rounds up to a
     * whole patch, so a pixel budget alone overshoots (1280×960 bills 1610
     * tokens): step down until the rounded grid fits.
     */
    fun target(width: Int, height: Int): Pair<Int, Int>? {
        var scale = minOf(1.0, MAX_LONG_EDGE.toDouble() / max(width, height))
        var w = (width * scale).roundToInt()
        var h = (height * scale).roundToInt()
        while (visualTokens(w, h) > MAX_VISUAL_TOKENS && scale > 0.05) {
            scale *= 0.98
            w = (width * scale).roundToInt()
            h = (height * scale).roundToInt()
        }
        return if (w >= width && h >= height) null else w to h
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun dataUri(jpeg: ByteArray): String = "data:image/jpeg;base64," + Base64.encode(jpeg)
}
