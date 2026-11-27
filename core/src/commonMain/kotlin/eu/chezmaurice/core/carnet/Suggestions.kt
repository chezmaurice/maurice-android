package eu.chezmaurice.core.carnet

import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.MauriceJson
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import eu.chezmaurice.core.domains.LenientIntOrNull
import eu.chezmaurice.core.domains.TolerantList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a conversation is worth keeping in the member's garden
 * (`server/src/services/entrySuggestions.ts`, `specs/carnet-suggestions.md`):
 * after each reply of a conversation alone with Maurice, the works and people
 * the exchange was about. Nothing reaches the garden until a row is kept.
 */
@Serializable
data class EntrySuggestion(
    val id: String,
    /** movies, series, books, music, podcasts, games, people, articles… */
    val kind: String,
    val title: String,
    @Serializable(with = LenientIntOrNull::class) val year: Int? = null,
    val subtitle: String? = null,
    val image: String? = null,
    /** The note that would be filed on the entry; empty for a shared article (keeping saves it). */
    val note: String = "",
    /** pending | kept | dismissed. */
    val state: String = "pending",
    /** Already an entry of the member's: keeping adds the note and nothing else. */
    val existing: Boolean = false,
    /**
     * A book of the household's library with no fiche yet: keeping it opens one
     * tied to that book. Absent from servers before 8 November 2026.
     */
    @SerialName("in_library") val inLibrary: Boolean? = null,
    /** Several possible identities: one must be picked to keep it. */
    val candidates: List<Candidate> = emptyList(),
    /** The garden entry, `<collection>/<locale>/<slug>`, once there is one: what Carnet opens. */
    val entry: String? = null,
    /** Where the entry reads in the member's garden, once there is one. */
    @SerialName("web_path") val webPath: String? = null,
    /** The last term searched for it. */
    val query: String? = null,
    /** Whether another look makes sense: not for the conversation's own entry, nor for someone from the member's life. */
    val searchable: Boolean? = null,
) {
    @Serializable
    data class Candidate(
        val id: String,
        val title: String,
        @Serializable(with = LenientIntOrNull::class) val year: Int? = null,
        val subtitle: String? = null,
        val image: String? = null,
    )

    val isKept: Boolean get() = state == "kept"
}

@Serializable
data class SuggestionList(
    /** A row that cannot be read is left out, the drawer keeps the others. */
    @Serializable(with = SuggestionRows::class) val suggestions: List<EntrySuggestion> = emptyList(),
    val pending: Int = 0,
    val kept: Int = 0,
    /** A from-text list's id, to read it again. */
    val batch: String? = null,
)

/** The drawer's rows, each read on its own. */
object SuggestionRows : KSerializer<List<EntrySuggestion>> by TolerantList(EntrySuggestion.serializer())

@Serializable private data class SearchAnswer(val found: Int = 0)

/**
 * A podcast's recent episodes, and the one Maurice reads the conversation as
 * being about (`GET /api/suggestions/:id/episodes`).
 */
@Serializable
data class EpisodeList(
    val feed: String = "",
    val episodes: List<Episode> = emptyList(),
    val suggested: String? = null,
) {
    @Serializable
    data class Episode(
        val id: String,
        val title: String = "",
        val date: String? = null,
        val description: String = "",
        val image: String = "",
        /** Seconds. */
        val duration: Int? = null,
    )
}

class SuggestionsApi(private val api: ApiClient) {
    /**
     * `GET /api/suggestions?conversation=` — the drawer's rows and its counts.
     * [settle]: answered once the pass the last reply started is done.
     */
    suspend fun list(conversationId: String, settle: Boolean = false): SuggestionList =
        api.get("/api/suggestions?conversation=${conversationId.encodeURLParameter()}" + if (settle) "&settle=1" else "")

    /** A show's episodes: [feed] the candidate picked when the row had several. Null when there is none to list. */
    suspend fun episodes(id: String, feed: String?): EpisodeList? = runCatching {
        api.get<EpisodeList>("/api/suggestions/${id.encodeURLPathPart()}/episodes" + (feed?.let { "?feed=${it.encodeURLParameter()}" } ?: ""))
    }.getOrNull()

    /**
     * Keep a row: [candidate] when it had several identities; [episodes] for
     * a podcast's episodes. The row as kept — its `entry` is the fiche to
     * open — or null when the answer does not read as one.
     */
    suspend fun keep(id: String, candidate: String? = null, episodes: List<String> = emptyList()): EntrySuggestion? {
        val text = api.send(HttpMethod.Post, "/api/suggestions/${id.encodeURLPathPart()}/keep", buildJsonObject {
            candidate?.let { put("candidate", it) }
            put("episodes", buildJsonArray { episodes.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }, timeoutMillis = 40_000).bodyAsText()
        return runCatching { MauriceJson.decodeFromString(EntrySuggestion.serializer(), text) }.getOrNull()
    }

    /**
     * `POST /api/suggestions/from-text` — what the member describes in their
     * own words ("the last Houellebecq", "Hannah Arendt"), named and looked up
     * as a conversation's subjects are, with no conversation. The rows are
     * kept, searched and dismissed by the same routes.
     */
    suspend fun fromText(text: String, locale: String?): SuggestionList =
        api.post("/api/suggestions/from-text", buildJsonObject {
            put("text", text)
            locale?.let { put("locale", it) }
        }, timeoutMillis = 60_000)

    /** `GET /api/suggestions?batch=` — a from-text list, read again. */
    suspend fun batch(batch: String): SuggestionList = api.get("/api/suggestions?batch=${batch.encodeURLParameter()}")

    /** Another look: [query] the member's own term; without it Maurice rephrases. The number found. */
    suspend fun search(id: String, query: String?): Int =
        api.post<SearchAnswer>("/api/suggestions/${id.encodeURLPathPart()}/search", buildJsonObject {
            query?.let { put("query", it) }
        }, timeoutMillis = 40_000).found

    suspend fun dismiss(id: String) {
        api.post<JsonObject>("/api/suggestions/${id.encodeURLPathPart()}/dismiss", buildJsonObject {})
    }
}
