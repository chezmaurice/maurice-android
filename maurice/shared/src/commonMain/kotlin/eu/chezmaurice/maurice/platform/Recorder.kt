package eu.chezmaurice.maurice.platform

import androidx.compose.runtime.Composable
import eu.chezmaurice.core.dictation.Recording

/** The microphone, the platform's way. One recording at a time. */
interface Recorder {
    /** False when the microphone could not be taken. */
    fun start(): Boolean
    /** The recording, or null if there was none. */
    fun stop(): Recording?
    fun cancel()
    /** Loudness now, 0 to 1, for the level meter. */
    fun level(): Float
}

@Composable
expect fun rememberRecorder(): Recorder

/**
 * Ask for the microphone where the platform wants it asked (Android); returns
 * the function to call before recording, which answers through [onResult].
 */
@Composable
expect fun rememberMicPermission(onResult: (Boolean) -> Unit): () -> Unit
