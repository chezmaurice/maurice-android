package eu.chezmaurice.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Participant(
    @SerialName("member_id") val memberId: String,
    val role: String = "member",
    val username: String? = null,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_color") val avatarColor: String = "#6b6460",
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/**
 * Whether this member may take someone else out of the room: the one who
 * opened it (`role: "owner"` among the participants) or the household's
 * admin. Anyone may take themselves out (`DELETE …/participants/:me`, `leave`).
 */
fun canRemoveOthers(participants: List<Participant>, me: String, isAdmin: Boolean): Boolean =
    isAdmin || participants.any { it.memberId == me && it.role == "owner" }

/** A row of `GET /api/conversations`, or what `POST`/`PATCH` return. */
@Serializable
data class Conversation(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    /** Null until the server titles it after the first exchange. */
    val title: String? = null,
    /** The domain or companion it is bound to; null is the everyday Maurice. */
    @SerialName("maurice_id") val mauriceId: String? = null,
    @SerialName("maurice_bound_by") val mauriceBoundBy: String? = null,
    val origin: String? = null,
    @SerialName("opened_by") val openedBy: String? = null,
    @SerialName("entry_ref") val entryRef: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("message_count") val messageCount: Int? = null,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
    val unread: Boolean? = null,
    val participants: List<Participant>? = null,
    val suggestions: Int? = null,
    @SerialName("suggestions_kept") val suggestionsKept: Int? = null,
    /**
     * Received from another household with an entry given here
     * (specs/cross-household-share.md, E): no composer, only a way to a new
     * one. Both absent from an older server.
     */
    @SerialName("read_only") val readOnly: Boolean? = null,
    /** That household's display name. */
    @SerialName("imported_from") val importedFrom: String? = null,
) {
    val isRoom: Boolean get() = (participants?.size ?: 0) > 1
}

/** A structured tool result shown beside the prose (`tool_data`, `messages[].data`). */
@Serializable
data class DataBlock(val tool: String, val data: JsonElement)

/** What a turn cost. `cost` is in euros, whatever the old field comments say. */
@Serializable
data class TurnUsage(
    val provider: String = "",
    val model: String = "",
    val rounds: Int = 0,
    val input: Int = 0,
    val output: Int = 0,
    @SerialName("cache_read") val cacheRead: Int = 0,
    @SerialName("cache_write") val cacheWrite: Int = 0,
    val cost: Double? = null,
    @SerialName("cost_uncached") val costUncached: Double? = null,
)

@Serializable
data class Message(
    val id: String,
    @SerialName("conversation_id") val conversationId: String? = null,
    /** "user", "assistant", or "system" (never shown). */
    val role: String,
    val content: String,
    val model: String? = null,
    /** The human author; null for Maurice. */
    @SerialName("author_id") val authorId: String? = null,
    @SerialName("maurice_id") val mauriceId: String? = null,
    val data: List<DataBlock>? = null,
    val usage: TurnUsage? = null,
    @SerialName("created_at") val createdAt: String = "",
) {
    val isAssistant: Boolean get() = role == "assistant"
}

@Serializable
data class ConversationEntry(
    val ref: String,
    val collection: String = "",
    val title: String = "",
    val image: String? = null,
    @SerialName("web_path") val webPath: String? = null,
)

/** `GET /api/conversations/{id}`. */
@Serializable
data class ConversationDetail(
    val id: String,
    val title: String? = null,
    @SerialName("maurice_id") val mauriceId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    val messages: List<Message> = emptyList(),
    val participants: List<Participant> = emptyList(),
    val entry: ConversationEntry? = null,
    /** Received from another household, read-only (see [Conversation.readOnly]). */
    @SerialName("read_only") val readOnly: Boolean? = null,
    @SerialName("imported_from") val importedFrom: String? = null,
)

@Serializable
data class SearchHit(
    val conversation: Conversation,
    val snippet: String = "",
    @SerialName("message_id") val messageId: String? = null,
    val hits: Int = 0,
)

@Serializable
data class SearchResults(val q: String, val results: List<SearchHit> = emptyList())
