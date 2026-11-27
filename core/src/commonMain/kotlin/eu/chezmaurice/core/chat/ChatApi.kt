package eu.chezmaurice.core.chat

import eu.chezmaurice.core.model.Conversation
import eu.chezmaurice.core.model.ConversationDetail
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.model.Participant
import eu.chezmaurice.core.model.SearchResults
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.net.toApiError
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What happened when a turn was asked for. */
sealed interface TurnStart {
    /** The turn runs; read its events. */
    data class Streaming(val events: Flow<TurnEvent>) : TurnStart
    /** A message to the humans of a room, without Maurice. */
    data object Posted : TurnStart
}

@kotlinx.serialization.Serializable
private data class FactState(val state: String)

/** `PATCH /api/conversations/:id`: only the title is read back (RenamedConversation). */
@kotlinx.serialization.Serializable
data class Renamed(val title: String? = null)

/** The conversation routes, `/api/conversations…`. */
class ChatApi(private val api: ApiClient) {

    suspend fun conversations(limit: Int = 40, before: Conversation? = null): List<Conversation> {
        val cursor = before?.let {
            "&before_at=${it.updatedAt.encodeURLParameter()}&before_id=${it.id.encodeURLParameter()}"
        }.orEmpty()
        return api.get("/api/conversations?limit=$limit$cursor")
    }

    suspend fun search(query: String, limit: Int = 40): SearchResults =
        api.get("/api/conversations/search?q=${query.encodeURLParameter()}&limit=$limit")

    suspend fun conversation(id: String): ConversationDetail = api.get("/api/conversations/$id")

    suspend fun create(mauriceId: String? = null): Conversation =
        api.post("/api/conversations", JsonObject(mapOf("maurice_id" to (mauriceId?.let(::JsonPrimitive) ?: JsonNull))))

    /** A new conversation held from a garden entry, its fiche in context (what Carnet's Maurice button opens). */
    suspend fun createFromEntry(entryRef: String): Conversation =
        api.post("/api/conversations", buildJsonObject { put("entry", entryRef) })

    /**
     * Let go of the entry a conversation is held from: the server takes its
     * fiche (and the book that came with it) out of the context too. Only
     * before the first message — 409 `started` after.
     */
    suspend fun releaseEntry(id: String) {
        api.send(HttpMethod.Delete, "/api/conversations/$id/entry")
    }

    /** The server's answer carries the title it kept (null: the one asked for). */
    suspend fun rename(id: String, title: String): Renamed =
        api.decode(api.send(HttpMethod.Patch, "/api/conversations/$id", buildJsonObject { put("title", title) }))

    suspend fun delete(id: String) {
        api.send(HttpMethod.Delete, "/api/conversations/$id")
    }

    suspend fun markRead(id: String) {
        api.send(HttpMethod.Post, "/api/conversations/$id/read", buildJsonObject {})
    }

    /** Read a shared page; without html the server fetches the URL itself (fails behind paywalls). */
    suspend fun readArticle(url: String): ReadArticle =
        api.post("/api/v1/articles/read", buildJsonObject { put("url", url) }, timeoutMillis = 45_000)

    /** The household's members as the active member may see them. */
    suspend fun roster(): List<Member> = api.get("/api/users")

    /** File a conversation under a domain, or take it out (explicit null: the night won't re-file it). */
    suspend fun setDomain(id: String, mauriceId: String?) {
        api.send(HttpMethod.Patch, "/api/conversations/$id/maurice", JsonObject(mapOf("maurice_id" to (mauriceId?.let(::JsonPrimitive) ?: JsonNull))))
    }

    suspend fun addParticipant(id: String, memberId: String): List<Participant> =
        api.post("/api/conversations/$id/participants", buildJsonObject { put("member_id", memberId) })

    /** Take someone out of the room (added by mistake, say). */
    suspend fun removeParticipant(id: String, memberId: String) {
        api.send(HttpMethod.Delete, "/api/conversations/$id/participants/$memberId")
    }

    suspend fun leave(id: String) {
        api.send(HttpMethod.Post, "/api/conversations/$id/leave", buildJsonObject {})
    }

    /** Report a message or a member of a shared room to the server's operator. */
    suspend fun report(id: String, targetType: String, targetId: String, reason: String, note: String?) {
        api.send(HttpMethod.Post, "/api/conversations/$id/reports", buildJsonObject {
            put("target_type", targetType); put("target_id", targetId); put("reason", reason)
            // A note of spaces is no note: the operator would receive an empty one (ChatService.report).
            note?.trim()?.takeIf { it.isNotEmpty() }?.let { put("note", it) }
        })
    }

    suspend fun block(memberId: String) {
        api.send(HttpMethod.Post, "/api/users/$memberId/block", buildJsonObject {})
    }

    /** "Maurice noted this about you": keep it or let it go. Answers the fact's new state. */
    suspend fun decideFact(id: String, keep: Boolean): String {
        val response: FactState = api.post("/api/life-facts/$id/${if (keep) "keep" else "dismiss"}", buildJsonObject {})
        return response.state
    }

    /** Fire and forget: dropping the request alone does not stop the server. */
    suspend fun stop(id: String) {
        runCatching { api.send(HttpMethod.Post, "/api/conversations/$id/turn/stop", buildJsonObject {}) }
    }

    /**
     * `POST …/messages`. With [summon] Maurice answers and the response is the
     * turn's stream; without, it is a plain post to the room. [mauriceId] is
     * always sent: it arms the conversation (null = the everyday Maurice).
     * Photos go as data URIs: one as `image`, several as `images`.
     */
    suspend fun send(
        conversationId: String,
        content: String,
        mauriceId: String?,
        summon: Boolean = true,
        images: List<String> = emptyList(),
        regenerate: Boolean = false,
    ): TurnStart {
        val body = JsonObject(buildMap {
            put("content", JsonPrimitive(content))
            put("summon", JsonPrimitive(summon))
            put("maurice_id", mauriceId?.let(::JsonPrimitive) ?: JsonNull)
            when {
                images.size == 1 -> put("image", JsonPrimitive(images.single()))
                images.size > 1 -> put("images", JsonArray(images.map(::JsonPrimitive)))
            }
            if (regenerate) put("regenerate", JsonPrimitive(true))
        })
        if (!summon) {
            api.send(HttpMethod.Post, "/api/conversations/$conversationId/messages", body)
            return TurnStart.Posted
        }
        return TurnStart.Streaming(stream(HttpMethod.Post, "/api/conversations/$conversationId/messages", body))
    }

    /**
     * `GET …/turn`: re-attach to the running turn (or one finished in the last
     * minute). Emits nothing when nothing is running (204).
     */
    fun follow(conversationId: String): Flow<TurnEvent> =
        stream(HttpMethod.Get, "/api/conversations/$conversationId/turn", null)

    /**
     * Read an NDJSON response line by line. A 409 (a reply already running)
     * surfaces as [ApiError] like any other failure; the caller re-attaches.
     */
    private fun stream(method: HttpMethod, path: String, body: JsonObject?): Flow<TurnEvent> = flow {
        api.http.prepareRequest(api.url(path)) {
            this.method = method
            api.token?.let { bearerAuth(it) }
            // The server pings every 15 s of silence; no cap on the whole turn.
            timeout {
                requestTimeoutMillis = Long.MAX_VALUE
                socketTimeoutMillis = 60_000
            }
            if (body != null) setBody(TextContent(MauriceJson.encodeToString(JsonObject.serializer(), body), ContentType.Application.Json))
        }.execute { response ->
            if (response.status == HttpStatusCode.NoContent) return@execute
            if (!response.status.isSuccess()) throw response.toApiError()
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                val event = TurnEvent.parse(line) ?: continue
                emit(event)
                if (event.isTerminal) break
            }
        }
    }
}
