package eu.chezmaurice.parity

import eu.chezmaurice.core.dictation.DictationApi
import eu.chezmaurice.core.dictation.Recording
import eu.chezmaurice.core.net.ApiClient
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlin.test.Test

/** Dictation through the household server (lot Settings). */
class SettingsDictationParity {
    @Test fun settingsDictation() = Parity.check("maurice", "settings-dictation") { c ->
        val slug = c.string("slug") ?: "d0"
        val api = DictationApi(ApiClient(Parity.baseUrl + "/" + slug, "tok-u1"))
        when (c.string("kind")) {
            "capability" -> {
                val cap = api.capability()
                mapOf("requests" to SettingsSupport.requests("/$slug/"), "available" to cap.available)
            }
            "transcribe" -> {
                // As Recorder.android hands it over: AAC in MPEG-4, "audio/mp4".
                val recording = Recording("AUDIO".encodeToByteArray(), "audio/mp4", (c["seconds"] as JsonPrimitive).double)
                val result = try { mapOf("text" to api.transcribe(recording)) } catch (e: Throwable) { mapOf("failed" to true) }
                mapOf("requests" to SettingsSupport.requests("/$slug/"), "result" to result)
            }
            else -> mapOf("unknown kind" to c.string("kind"))
        }
    }
}
