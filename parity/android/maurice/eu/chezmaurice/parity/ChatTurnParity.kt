package eu.chezmaurice.parity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

/** One turn, end to end (parity/vectors/maurice/chat-turn.json); see ChatTurnParity.swift. */
class ChatTurnParity {
    @Test fun chatTurn() = Parity.check("maurice", "chat-turn") { c ->
        val scope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
        val chat = ChatSupport.controller(scope)
        val replies = ChatSupport.Replies(chat, scope)
        try {
            chat.loadConversations().join()
            val openId = if ((c.input as? kotlinx.serialization.json.JsonObject)?.get("open") is JsonNull) null else c.string("open") ?: "c1"
            if (openId != null) { chat.open(openId); ChatSupport.settle(chat) }
            delay(300)
            Parity.reset(ChatSupport.after(c["after"]))

            when (c.string("action")) {
                "send" -> {
                    val images = c.array("images").orEmpty().map { "data:image/jpeg;base64," + (it as JsonPrimitive).content }
                    chat.send(c.string("text").orEmpty(), images = images)
                }
                "regenerate" -> chat.regenerate()
                "stop" -> {
                    chat.send(c.string("text").orEmpty())
                    delay(300)
                    chat.stop()
                }
            }
            ChatSupport.settle(chat)
            val log = ChatSupport.log()
            val s = chat.state.value
            mapOf(
                "sent" to ChatSupport.sent(log),
                "attached" to ChatSupport.attached(log),
                "reply" to replies.observed(),
                "trails" to ChatSupport.trails(chat),
                "thread" to ChatSupport.thread(chat, ids = false),
                "banner" to ChatSupport.banner(chat),
                "running" to (s.turnRunning || chat.live.value != null),
                "active" to s.activeId,
            )
        } finally {
            scope.cancel()
        }
    }
}
