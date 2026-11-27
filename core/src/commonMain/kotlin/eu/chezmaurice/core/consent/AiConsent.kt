package eu.chezmaurice.core.consent

import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A provider outside the house that a member's data can reach, as GET /api/me/ai-consent names it. */
@Serializable
data class AiProvider(
    val id: String,
    val name: String,
    /** ISO region code of where it processes. */
    val region: String? = null,
    val city: String? = null,
)

@Serializable
data class AiConsentRecord(
    /** "accepted" or "declined". */
    val status: String,
    val providers: List<String> = emptyList(),
    @SerialName("decided_at") val decidedAt: String = "",
)

@Serializable
data class AiConsentAnswer(
    val record: AiConsentRecord? = null,
    val providers: List<AiProvider> = emptyList(),
    @SerialName("needs_answer") val needsAnswer: Boolean = false,
    val uncovered: List<String> = emptyList(),
)

data class AiConsentState(
    val answer: AiConsentAnswer? = null,
    /** Whether the last refresh reached the server at all. */
    val loaded: Boolean = false,
    /** The member asked to see the question again (the composer's banner). */
    val reviewing: Boolean = false,
    /** The server predates the route (404): it holds no rule and asks nothing. */
    val unsupported: Boolean = false,
) {
    val record: AiConsentRecord? get() = answer?.record
    val providers: List<AiProvider> get() = answer?.providers.orEmpty()
    val accepted: Boolean get() = record?.status == "accepted"
    val needsAnswer: Boolean get() = loaded && answer?.needsAnswer == true
    /** Whether the consent screen is up. */
    val showsQuestion: Boolean get() = needsAnswer || reviewing
    /** Providers that appeared since the member's last yes. */
    val newProviders: List<AiProvider> get() = answer?.let { a -> a.providers.filter { it.id in a.uncovered } }.orEmpty()

    /**
     * May Maurice be asked anything? Yes with no provider outside the house,
     * or once every provider in use is accepted. Not before the server has
     * answered, nor when it could not be asked: silence is never a yes
     * (AIConsentStore.mayUseAI). A server from before the route holds no
     * rule, and nothing is held back.
     */
    val mayUseAI: Boolean get() {
        val a = answer ?: return unsupported
        if (a.providers.isEmpty()) return true
        return accepted && !a.needsAnswer
    }

    /** The answer is no: the composer says so and sends nothing. */
    val declined: Boolean get() = record?.status == "declined"

    fun covers(provider: String): Boolean = provider == "ollama" || (accepted && record?.providers?.contains(provider) == true)
}

/**
 * Consent to send what the member gives Maurice to AI providers, as
 * `AIConsentStore.swift`. The server holds the answer and the rule
 * (services/aiConsent.ts); the app asks before anything is sent, shows the
 * answer in Settings → My data, and keeps the composer quiet while the
 * answer is no. A server from before the route (404) has no question to ask:
 * nothing is shown, and that server does not hold the rule either.
 */
class AiConsentStore(private val api: ApiClient) {
    private val _state = MutableStateFlow(AiConsentState())
    val state: StateFlow<AiConsentState> = _state.asStateFlow()

    suspend fun refresh() {
        try {
            val answer: AiConsentAnswer = api.get("/api/me/ai-consent")
            _state.value = _state.value.copy(answer = answer, loaded = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            if (e.status == 404) _state.value = AiConsentState(unsupported = true)
            // Otherwise unreachable: keep what we had; never treat silence as a yes.
        } catch (_: Exception) {
        }
    }

    fun review() { _state.value = _state.value.copy(reviewing = true) }

    /** Accept (every provider shown, plus any already accepted) or decline. */
    suspend fun decide(accept: Boolean) {
        val s = _state.value
        val ids = (s.providers.map { it.id } + s.record?.providers.orEmpty()).toSortedSet().toList()
        put(if (accept) "accepted" else "declined", ids)
        _state.value = _state.value.copy(reviewing = false)
    }

    /** Add one provider to an existing yes: the domain editor's question. */
    suspend fun accept(provider: String) {
        val s = _state.value
        val ids = ((if (s.accepted) s.record?.providers.orEmpty() else emptyList()) + provider).toSortedSet().toList()
        put("accepted", ids)
    }

    private suspend fun put(status: String, ids: List<String>) {
        val body = buildJsonObject {
            put("status", status)
            put("providers", JsonArray(ids.map(::JsonPrimitive)))
        }
        runCatching {
            api.decode<AiConsentAnswer>(api.send(io.ktor.http.HttpMethod.Put, "/api/me/ai-consent", body))
        }.onSuccess { _state.value = _state.value.copy(answer = it, loaded = true) }
    }

    companion object {
        /** The names the server gives (services/aiConsent.ts), for a provider it did not list. */
        fun knownProvider(id: String): AiProvider = when (id) {
            "scaleway" -> AiProvider(id, "Scaleway Generative APIs", "FR", "Paris")
            "mistral" -> AiProvider(id, "Mistral AI", "FR", "Paris")
            "anthropic" -> AiProvider(id, "Anthropic", "US")
            "openai" -> AiProvider(id, "OpenAI", "US")
            "gemini" -> AiProvider(id, "Google Gemini", "US")
            "zai" -> AiProvider(id, "Z.ai (Zhipu AI)", "CN")
            "fal" -> AiProvider(id, "fal.ai (image generation)", "US")
            "tavily" -> AiProvider(id, "Tavily (web search)", "US")
            else -> AiProvider(id, id)
        }
    }
}
