package eu.chezmaurice.core.chat

import eu.chezmaurice.core.model.DataBlock
import eu.chezmaurice.core.model.TurnUsage
import eu.chezmaurice.core.net.MauriceJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * One line of a turn's NDJSON stream. Unknown types and undecodable lines
 * are dropped, as the iOS app does.
 */
sealed interface TurnEvent {
    data class TextDelta(val text: String) : TurnEvent
    data object Thinking : TurnEvent
    data object Ping : TurnEvent
    data class ToolCall(val tool: String, val started: Boolean) : TurnEvent
    data class ToolData(val block: DataBlock) : TurnEvent
    data class ImageLoading(val prompt: String) : TurnEvent
    data class Image(val url: String, val prompt: String) : TurnEvent
    data class Usage(val usage: TurnUsage) : TurnEvent
    /** Terminal: the reply is stored under [messageId] (null from a server that does not say). */
    data class Done(val messageId: String?) : TurnEvent
    /** Terminal for the app: provider failure, budget refusal… */
    data class Error(val message: String) : TurnEvent
    /** First line of a re-attached stream: everything so far. */
    data class Resume(
        val text: String,
        val data: List<DataBlock>,
        val tool: String?,
        val usage: TurnUsage?,
        val startedAt: String?,
        val finished: Boolean,
    ) : TurnEvent

    val isTerminal: Boolean get() = this is Done || this is Error

    companion object {
        fun parse(line: String): TurnEvent? {
            if (line.isBlank()) return null
            val raw = runCatching { MauriceJson.decodeFromString(RawEvent.serializer(), line) }.getOrNull() ?: return null
            return when (raw.type) {
                "text_delta" -> TextDelta(raw.text.orEmpty())
                "thinking" -> Thinking
                "ping" -> Ping
                // As ChatService.render: a step starts only on "start" with a
                // tool named; any other status (end, error, none) ends it.
                "tool_call" -> ToolCall(raw.tool.orEmpty(), raw.status == "start" && raw.tool != null)
                // A data event with no data adds nothing; one with no tool is "tool".
                "tool_data" -> raw.data?.takeUnless { it is JsonNull }?.let { ToolData(DataBlock(raw.tool ?: "tool", it)) }
                "image_loading" -> ImageLoading(raw.text.orEmpty())
                "image" -> raw.imageUrl?.let { Image(it, raw.text ?: "image") }
                "usage" -> raw.usage?.let { Usage(it) }
                // The reply ends even when the server names no message (iOS
                // makes up an id): never a reason to re-attach.
                "done" -> Done(raw.messageId)
                "error" -> Error(raw.message ?: "Stream error")
                "resume" -> Resume(
                    text = raw.text.orEmpty(),
                    // One malformed block must not cost the whole snapshot (LenientBlock).
                    data = (raw.data as? JsonArray).orEmpty().mapNotNull { block ->
                        runCatching { MauriceJson.decodeFromJsonElement(DataBlock.serializer(), block) }.getOrNull()
                    },
                    tool = raw.tool,
                    usage = raw.usage,
                    startedAt = raw.startedAt,
                    finished = raw.finished ?: false,
                )
                else -> null
            }
        }
    }
}

@Serializable
private data class RawEvent(
    val type: String,
    val text: String? = null,
    val tool: String? = null,
    val status: String? = null,
    val data: JsonElement? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val usage: TurnUsage? = null,
    @SerialName("message_id") val messageId: String? = null,
    val message: String? = null,
    val error: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    val finished: Boolean? = null,
)
