package eu.chezmaurice.core.chat

import eu.chezmaurice.core.model.Message
import eu.chezmaurice.core.model.Participant
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.MauriceJson
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.encodeURLParameter
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the member's own socket (`/api/me/ws`) says. */
sealed interface UserEvent {
    /** Someone added the member to a conversation. */
    data class Added(val conversationId: String, val title: String?, val by: String?) : UserEvent
    /** Maurice opened a conversation on his own. */
    data class Opened(val conversationId: String, val title: String?, val author: String?, val preview: String?) : UserEvent
    /** Someone wrote in one of the member's conversations. */
    data class Activity(val conversationId: String, val title: String?, val author: String?, val preview: String?) : UserEvent
    /** Carnet suggestions pending in a conversation. */
    data class Suggestions(val conversationId: String, val count: Int, val kept: Int) : UserEvent
}

/** What a conversation's socket (`/api/conversations/{id}/ws`) says. */
sealed interface RoomEvent {
    data class NewMessage(val message: Message) : RoomEvent
    data class Participants(val participants: List<Participant>) : RoomEvent
    /** Someone else summoned Maurice; his reply has not arrived yet. */
    data class Summoned(val by: String) : RoomEvent
}

@Serializable
private data class RawSocketEvent(
    val type: String,
    val conversationId: String? = null,
    val title: String? = null,
    val by: String? = null,
    val author: String? = null,
    val preview: String? = null,
    val count: Int = 0,
    val kept: Int = 0,
    val message: Message? = null,
    val participants: List<Participant>? = null,
)

internal fun parseUserEvent(text: String): UserEvent? {
    val raw = runCatching { MauriceJson.decodeFromString(RawSocketEvent.serializer(), text) }.getOrNull() ?: return null
    val id = raw.conversationId ?: return null
    return when (raw.type) {
        "conversation_added" -> UserEvent.Added(id, raw.title, raw.by)
        "conversation_opened" -> UserEvent.Opened(id, raw.title, raw.author, raw.preview)
        "activity" -> UserEvent.Activity(id, raw.title, raw.author, raw.preview)
        "suggestions" -> UserEvent.Suggestions(id, raw.count, raw.kept)
        else -> null
    }
}

internal fun parseRoomEvent(text: String): RoomEvent? {
    val raw = runCatching { MauriceJson.decodeFromString(RawSocketEvent.serializer(), text) }.getOrNull() ?: return null
    return when (raw.type) {
        "message" -> raw.message?.let { RoomEvent.NewMessage(it) }
        "participants" -> raw.participants?.let { RoomEvent.Participants(it) }
        "summoned" -> raw.by?.let { RoomEvent.Summoned(it) }
        else -> null
    }
}

/**
 * A receive-only socket that keeps itself open — `RoomSocket`/`UserSocket` on
 * iOS: on any failure it reconnects after 1 s, doubling to 30 s, back to 1 s
 * at the first frame. The bus does not replay what was missed, so
 * [onReconnected] lets the owner read again. The token rides in the query:
 * a socket cannot carry the header everywhere.
 */
class LiveSocket<E>(
    private val api: ApiClient,
    private val path: String,
    private val parse: (String) -> E?,
    private val onEvent: (E) -> Unit,
    private val onReconnected: () -> Unit = {},
    /**
     * The server shut this socket for good: the member left the room or was
     * taken out of it (close code 1008), or it refuses to open it again (403).
     * Not tried again (26 November 2026).
     */
    private val onShut: () -> Unit = {},
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            var backoff = 1_000L
            var first = true
            while (true) {
                var shut = false
                try {
                    // No request timeout on a socket meant to stay open.
                    api.http.webSocket(urlString = socketUrl(), request = { timeout { requestTimeoutMillis = Long.MAX_VALUE } }) {
                        if (!first) onReconnected()
                        first = false
                        for (frame in incoming) {
                            backoff = 1_000L
                            if (frame !is Frame.Text) continue
                            parse(frame.readText())?.let(onEvent)
                        }
                        shut = isShut(closeReason.await())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Dropped or unreachable: try again below. Refused (403): not again.
                    shut = isRefused(e)
                }
                if (shut) { onShut(); return@launch }
                first = false
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    internal companion object {
        /** Policy violation: the server's way of saying "no longer yours" (RFC 6455, 1008). */
        fun isShut(reason: CloseReason?): Boolean = reason?.code == CloseReason.Codes.VIOLATED_POLICY.code

        /** The handshake answered 403 (Ktor: "…expected status code 101 but was 403"). */
        fun isRefused(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.take(6).any { it.message?.contains(Regex("""\b403\b""")) == true }
    }

    private fun socketUrl(): String {
        val base = api.baseUrl.trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        return "$base$path?token=" + (api.token ?: "").encodeURLParameter()
    }
}
