package eu.chezmaurice.parity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlin.test.Test

/** A conversation opened, and what can be done with it (chat-thread.json); see ChatThreadParity.swift. */
class ChatThreadParity {
    @Test fun chatThread() = Parity.check("maurice", "chat-thread") { c ->
        val scope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
        val chat = ChatSupport.controller(scope)
        try {
            chat.loadConversations().join()
            c.string("open")?.let { chat.open(it); ChatSupport.settle(chat) }
            delay(300)
            Parity.reset(ChatSupport.after(c["after"]))

            var result: Any? = null
            val action = c.string("action")
            when (action) {
                "open" -> chat.open(c.string("id").orEmpty())
                "rename" -> chat.rename(c.string("id").orEmpty(), c.string("title").orEmpty()).join()
                "delete" -> chat.delete(c.string("id").orEmpty()).join()
                "search" -> chat.search(c.string("q").orEmpty())
                "more" -> repeat(c.int("times") ?: 1) { chat.loadMore(); ChatSupport.settle(chat) }
                "add" -> chat.addParticipant(c.string("member").orEmpty())
                "leave" -> chat.leave()
                "block" -> chat.block(c.string("member").orEmpty())
                "domain" -> result = chat.setDomain(c.string("domain"))
                "report" -> result = chat.report("message", c.string("target").orEmpty(), c.string("reason").orEmpty(), c.string("note"))
            }
            ChatSupport.settle(chat)
            val log = ChatSupport.log()
            val s = chat.state.value
            if (action == "search") result = mapOf("results" to s.searchResults.orEmpty().map {
                mapOf("id" to it.conversation.id, "snippet" to it.snippet, "hits" to it.hits, "message" to it.messageId)
            })
            buildMap {
                put("sent", ChatSupport.sent(log))
                put("active", s.activeId)
                put("list", ChatSupport.list(chat))
                put("unread", s.conversations.filter { it.unread == true }.map { it.id }.sorted())
                put("thread", ChatSupport.thread(chat, ids = true))
                put("participants", ChatSupport.participants(chat))
                put("room", s.participants.size > 1)
                put("entry", s.activeEntry?.ref)
                put("banner", ChatSupport.banner(chat))
                put("result", result)
                if (action == "search" || action == "more") put("reads", ChatSupport.listReads(log))
            }
        } finally {
            scope.cancel()
        }
    }
}
