package eu.chezmaurice.maurice.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import eu.chezmaurice.core.dictation.Recording
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import kotlin.math.sqrt

/** 16 kHz mono 16-bit PCM, sent as WAV (`audio/wav`), which the server takes as is. */
private class DesktopRecorder : Recorder {
    private val format = AudioFormat(16_000f, 16, 1, true, false)
    private var line: TargetDataLine? = null
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()
    @Volatile private var lastLevel = 0f

    override fun start(): Boolean = runCatching {
        val l = AudioSystem.getTargetDataLine(format)
        l.open(format); l.start()
        pcm.reset(); line = l
        thread = Thread {
            val buffer = ByteArray(3200) // 0.1 s
            while (line === l) {
                val n = l.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                synchronized(pcm) { pcm.write(buffer, 0, n) }
                val samples = ByteBuffer.wrap(buffer, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                var sum = 0.0
                for (i in 0 until samples.remaining()) { val s = samples.get(i) / 32768.0; sum += s * s }
                lastLevel = minOf(1f, (sqrt(sum / maxOf(1, samples.remaining())) * 4).toFloat())
            }
        }.apply { isDaemon = true; start() }
        true
    }.getOrElse { cancel(); false }

    override fun stop(): Recording? {
        val l = line ?: return null
        line = null
        l.stop(); l.close()
        thread?.join(500)
        val data = synchronized(pcm) { pcm.toByteArray() }
        val seconds = data.size / (16_000.0 * 2)
        return Recording(wav(data), "audio/wav", seconds)
    }

    override fun cancel() {
        line?.let { it.stop(); it.close() }
        line = null
    }

    override fun level(): Float = lastLevel

    private fun wav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }
        return header.array() + pcm
    }
}

@Composable
actual fun rememberRecorder(): Recorder = remember { DesktopRecorder() }

/** The desktop asks nothing beforehand; the system may prompt on first use. */
@Composable
actual fun rememberMicPermission(onResult: (Boolean) -> Unit): () -> Unit = { onResult(true) }
