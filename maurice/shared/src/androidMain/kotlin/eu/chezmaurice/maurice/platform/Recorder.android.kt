package eu.chezmaurice.maurice.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import eu.chezmaurice.core.dictation.Recording
import java.io.File

/** AAC in MPEG-4, 16 kHz mono, ~32 kbit/s — what the iOS app sends (`audio/mp4`). */
private class AndroidRecorder(private val context: Context) : Recorder {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    override fun start(): Boolean = runCatching {
        val out = File.createTempFile("dictation", ".m4a", context.cacheDir)
        val r = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16_000)
            setAudioChannels(1)
            setAudioEncodingBitRate(32_000)
            setOutputFile(out.absolutePath)
            prepare()
            start()
        }
        recorder = r; file = out; startedAt = SystemClock.elapsedRealtime()
        true
    }.getOrElse { cancel(); false }

    override fun stop(): Recording? {
        val r = recorder ?: return null
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        val ok = runCatching { r.stop() }.isSuccess
        r.release(); recorder = null
        val f = file; file = null
        if (!ok || f == null) { f?.delete(); return null }
        return Recording(f.readBytes(), "audio/mp4", seconds).also { f.delete() }
    }

    override fun cancel() {
        runCatching { recorder?.stop() }
        recorder?.release(); recorder = null
        file?.delete(); file = null
    }

    override fun level(): Float = (recorder?.maxAmplitude ?: 0) / 32_767f
}

@Composable
actual fun rememberRecorder(): Recorder {
    val context = LocalContext.current
    return remember { AndroidRecorder(context.applicationContext) }
}

@Composable
actual fun rememberMicPermission(onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) onResult(true)
        else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
}
