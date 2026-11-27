package eu.chezmaurice.parity

import eu.chezmaurice.core.dictation.DictationApi
import eu.chezmaurice.core.dictation.Recording
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

class ReadingDictationParity {
    @Test fun readingDictation() = Parity.check("carnet", "reading-dictation") { c ->
        val dictation = DictationApi(ReadingFixture.api())
        // What the Android recorder hands over (Recorder.android.kt): AAC in MPEG-4.
        val recording = Recording((c.string("audio") ?: "").encodeToByteArray(), "audio/mp4", c["seconds"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
        val text = try { dictation.transcribe(recording) } catch (e: Throwable) { if (e is AssertionError) throw e; null }
        mapOf("requests" to Parity.requests(), "result" to (text ?: "failed"))
    }
}
