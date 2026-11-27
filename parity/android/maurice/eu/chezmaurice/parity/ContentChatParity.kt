package eu.chezmaurice.parity

import eu.chezmaurice.core.carnet.SuggestionRows
import eu.chezmaurice.core.chat.ToolStep
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.maurice.chat.toolLabel
import eu.chezmaurice.maurice.chat.toolName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlin.test.Test

/** A tool's words, a library book in the suggestions, taking someone out of a room (content-chat.json). */
class ContentChatParity {
    @Test fun contentChat() = Parity.check("maurice", "content-chat") { c ->
        when (c.string("op")) {
            "tool" -> {
                val tool = c.string("tool").orEmpty()
                val running = c.bool("running") ?: false
                mapOf(
                    "label" to ChatSupport.strings.toolLabel(tool, running),
                    "name" to ChatSupport.strings.toolName(ToolStep(tool, c.int("count") ?: 1, running)),
                )
            }
            "suggestion" -> {
                val rows = runCatching { MauriceJson.decodeFromString(SuggestionRows, c.text("rows")) }.getOrNull()
                mapOf("rows" to (rows?.map { mapOf("id" to it.id, "existing" to it.existing, "inLibrary" to it.inLibrary) } ?: "undecodable"))
            }
            "remove" -> {
                val scope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
                val chat = ChatSupport.controller(scope)
                try {
                    chat.loadConversations().join()
                    c.string("open")?.let { chat.open(it); ChatSupport.settle(chat) }
                    delay(300)
                    Parity.reset(ChatSupport.after(c["after"]))
                    chat.removeParticipant(c.string("member").orEmpty()).join()
                    ChatSupport.settle(chat)
                    mapOf(
                        "sent" to ChatSupport.sent(ChatSupport.log()),
                        "participants" to ChatSupport.participants(chat),
                        "banner" to ChatSupport.banner(chat),
                    )
                } finally {
                    scope.cancel()
                }
            }
            else -> mapOf("unknown op" to c.string("op"))
        }
    }
}
