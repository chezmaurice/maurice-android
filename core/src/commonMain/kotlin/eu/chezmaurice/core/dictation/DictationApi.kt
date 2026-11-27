package eu.chezmaurice.core.dictation

import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.net.MauriceJson
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DictationCapability(
    val available: Boolean = false,
    /** "local" (whisper.cpp in the house) or "scaleway" (metered cloud), or null. */
    val engine: String? = null,
    @SerialName("max_seconds") val maxSeconds: Int = 600,
)

@Serializable
private data class Transcript(val text: String? = null, val engine: String? = null, val seconds: Double? = null)

/** A recording, as the platform made it. */
class Recording(val bytes: ByteArray, val contentType: String, val seconds: Double)

/**
 * The household's dictation — `/api/dictation`: Whisper hears the whole
 * recording and detects the language itself. Dictation fills the field and
 * never sends anything on its own.
 */
class DictationApi(private val api: ApiClient) {

    /** `GET /api/dictation`; a server too old to have it (404) is "not available". */
    suspend fun capability(): DictationCapability = runCatching {
        api.get<DictationCapability>("/api/dictation")
    }.getOrDefault(DictationCapability())

    /** The words, or "" for silence. Failures are [eu.chezmaurice.core.net.ApiError]s with the server's sentence. */
    suspend fun transcribe(recording: Recording): String {
        // "%.1f", as iOS sends it: 0.35 s goes as "0.3", 600 s as "600.0".
        val seconds = eu.chezmaurice.core.text.printfFixed(recording.seconds, 1)
        val response = api.send(HttpMethod.Post, "/api/dictation?duration=$seconds", timeoutMillis = 240_000) {
            setBody(ByteArrayContent(recording.bytes, ContentType.parse(recording.contentType)))
        }
        // An answer without its words is a failure, not silence (HouseholdTranscription on
        // iOS): the member is told the recording could not be transcribed.
        val text = runCatching { MauriceJson.decodeFromString(Transcript.serializer(), response.bodyAsText()).text }.getOrNull()
            ?: throw ApiError(0, TRANSCRIPTION_FAILED)
        return text.trim()
    }

    companion object {
        /** A clip this short is "nothing said" and is not sent (as on iOS). */
        const val MIN_SECONDS = 0.3
        /** The words the member is shown when the server answered without a transcript (a string key). */
        const val TRANSCRIPTION_FAILED = "dictation.error.transcription_failed"
    }
}
