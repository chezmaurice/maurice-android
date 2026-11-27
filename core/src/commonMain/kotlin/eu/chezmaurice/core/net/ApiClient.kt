package eu.chezmaurice.core.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Lenient on purpose: the server adds fields, and old builds must keep working. */
val MauriceJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

/**
 * One household server, seen as one member. Every helper attaches the
 * member's token when there is one, except where [authenticated] is false:
 * the server rejects a stale token even on its open routes.
 */
class ApiClient(
    val baseUrl: String,
    val token: String?,
    val http: HttpClient = defaultHttpClient(),
    internal val onUnauthorized: () -> Unit = {},
) {
    fun url(path: String): String = baseUrl.trimEnd('/') + path

    suspend fun send(
        method: HttpMethod,
        path: String,
        body: JsonElement? = null,
        authenticated: Boolean = true,
        timeoutMillis: Long? = null,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        val response = http.request(url(path)) {
            this.method = method
            if (authenticated && token != null) bearerAuth(token)
            // The socket's too: OkHttp's own read timeout (10 s) would cut a
            // reply the server takes its time over (a summary, a page fetched).
            if (timeoutMillis != null) timeout { requestTimeoutMillis = timeoutMillis; socketTimeoutMillis = timeoutMillis }
            // Serialised here, not by content negotiation: a body typed as
            // a supertype loses its serializer on the way there.
            if (body != null) setBody(TextContent(MauriceJson.encodeToString(JsonElement.serializer(), body), ContentType.Application.Json))
            configure()
        }
        if (!response.status.isSuccess()) throw response.toApiError().also {
            if (it.endsSession && authenticated && token != null) onUnauthorized()
        }
        return response
    }

    suspend inline fun <reified T> get(path: String, authenticated: Boolean = true): T =
        decode(send(HttpMethod.Get, path, authenticated = authenticated))

    suspend inline fun <reified T> post(path: String, body: JsonElement? = null, authenticated: Boolean = true, timeoutMillis: Long? = null): T =
        decode(send(HttpMethod.Post, path, body ?: JsonObject(emptyMap()), authenticated, timeoutMillis))

    suspend inline fun <reified T> decode(response: HttpResponse): T =
        MauriceJson.decodeFromString(response.bodyAsText())

    companion object {
        fun defaultHttpClient(): HttpClient = HttpClient {
            expectSuccess = false
            install(ContentNegotiation) { json(MauriceJson) }
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 60_000
                // Without it OkHttp reads with its own 10 s, shorter than the request's.
                socketTimeoutMillis = 60_000
            }
            // The server pings every 40 s on the sockets; answer its pings, send none.
            install(WebSockets)
        }
    }
}

suspend fun HttpResponse.toApiError(): ApiError {
    val text = runCatching { bodyAsText() }.getOrDefault("")
    val body = runCatching { MauriceJson.parseToJsonElement(text).jsonObject }.getOrNull()
    val said = runCatching { body?.get("error")?.jsonPrimitive?.content }.getOrNull()
    val code = runCatching { body?.get("code")?.jsonPrimitive?.content }.getOrNull()
    return ApiError(status.value, said ?: text.ifBlank { status.description }, headers["Retry-After"]?.toIntOrNull(), fromServer = said != null, code = code)
}
