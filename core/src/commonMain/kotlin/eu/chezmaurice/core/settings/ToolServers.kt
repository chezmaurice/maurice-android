package eu.chezmaurice.core.settings

import eu.chezmaurice.core.net.ApiClient
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An MCP server the member connected themselves (`/api/tool-servers`,
 * `ToolServerRow` on iOS): an address and, when it wants one, a token or a
 * sign-in in the browser. The token never comes back.
 */
@Serializable
data class ToolServer(
    val id: String,
    val name: String,
    val url: String,
    /** `x_<slug>`: how the model sees its tools. */
    val prefix: String? = null,
    @SerialName("has_token") val hasToken: Boolean = false,
    /** "oauth" when the member signed in to it in a browser; "token" otherwise. */
    val auth: String? = null,
    val enabled: Boolean = true,
    /** unchecked | ok | error. */
    val state: String = "unchecked",
    @SerialName("last_error") val lastError: String? = null,
    val tools: List<Tool> = emptyList(),
    /** How many tools the server offered beyond what is kept. */
    val dropped: Int = 0,
) {
    @Serializable data class Tool(val name: String, val description: String = "")

    val signsIn: Boolean get() = auth == "oauth"

    /** The host alone, as the card shows it; the whole address when it has none. */
    val host: String get() = url.substringAfter("://", url).substringBefore('/').substringBefore('?').ifEmpty { url }
}

/** The member's servers, and whether this member may add any (guests and a switched-off household may not). */
@Serializable
data class ToolServers(val servers: List<ToolServer> = emptyList(), val allowed: Boolean = true)

@Serializable private data class SignInWhere(@SerialName("authorize_url") val authorizeUrl: String)

/**
 * The member's own tools (`ToolsPane` on iOS). Every route acts on the
 * caller's servers only. Adding one asks it for its tools before answering:
 * 422 with the reason when it did not answer or refused the token, and
 * nothing is kept; 428 when it wants a sign-in instead of a token, which
 * [signIn] then starts.
 */
class ToolServersApi(private val api: ApiClient) {

    /** Null when the server could not be read. */
    suspend fun list(): ToolServers? = try {
        api.get("/api/tool-servers")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    /** No token: the server is reached as it is, or answers 428 to be signed in to. */
    suspend fun add(name: String, url: String, token: String?): ToolServer =
        api.post("/api/tool-servers", buildJsonObject {
            put("name", name); put("url", url)
            if (!token.isNullOrEmpty()) put("token", token)
        }, timeoutMillis = 40_000)

    /**
     * Where to send the member's browser to sign in to a new server. The
     * service brings the browser back to the household, not to the app: the
     * server appears in [list] once that is done.
     */
    suspend fun signIn(name: String, url: String): String =
        api.post<SignInWhere>("/api/tool-servers/oauth/start", buildJsonObject { put("name", name); put("url", url) }, timeoutMillis = 40_000).authorizeUrl

    /** The same, for one of the member's servers whose sign-in lapsed. */
    suspend fun signInAgain(id: String): String =
        api.post<SignInWhere>("/api/tool-servers/oauth/start", buildJsonObject { put("id", id) }, timeoutMillis = 40_000).authorizeUrl

    suspend fun setEnabled(id: String, enabled: Boolean): ToolServer =
        api.decode(api.send(HttpMethod.Patch, path(id), buildJsonObject { put("enabled", enabled) }, timeoutMillis = 40_000))

    /** Tried first: a token that does not work leaves the previous one in place (422). */
    suspend fun setToken(id: String, token: String): ToolServer =
        api.decode(api.send(HttpMethod.Patch, path(id), buildJsonObject { put("token", token) }, timeoutMillis = 40_000))

    /** Ask the server again what it offers; the row says how it went. */
    suspend fun refresh(id: String): ToolServer = api.post(path(id) + "/refresh", timeoutMillis = 40_000)

    suspend fun remove(id: String) { api.send(HttpMethod.Delete, path(id)) }

    private fun path(id: String) = "/api/tool-servers/${id.encodeURLPathPart()}"
}

/** "https://" before an address typed without a scheme, as iOS does. */
fun toolServerAddress(typed: String): String = typed.trim().let { if (it.contains("://")) it else "https://$it" }
