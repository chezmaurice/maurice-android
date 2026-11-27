package eu.chezmaurice.parity

import eu.chezmaurice.core.chat.ChatApi
import eu.chezmaurice.core.chat.ChatController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

class ConversationListParity {
    @Test fun conversationList() = Parity.check("maurice", "conversation-list") { _ ->
        val session = MauriceFixture.session()
        val scope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
        val chat = ChatController(ChatApi(session.api()!!), scope, "u1")
        chat.loadConversations().join()
        val s = chat.state.value
        val requests = (Parity.requests() as JsonArray)
            .filter { it.jsonObject["path"]?.jsonPrimitive?.content?.startsWith("/api/conversations") == true }
        mapOf(
            "requests" to requests,
            "conversations" to s.conversations.map { mapOf("id" to it.id, "title" to it.title) },
            "unread" to s.conversations.filter { it.unread == true }.map { it.id }.sorted(),
            "error" to (s.error != null),
        )
    }
}
