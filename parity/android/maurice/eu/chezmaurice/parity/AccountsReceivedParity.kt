package eu.chezmaurice.parity

import eu.chezmaurice.parity.Parity.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlin.test.Test

/** A conversation received read-only from another household (accounts-received.json); see AccountsReceivedParity.swift. */
class AccountsReceivedParity {
    @Test fun received() = Parity.check("maurice", "accounts-received") { c ->
        val scope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
        val chat = ChatSupport.controller(scope)
        try {
            chat.loadConversations().join()
            c.string("open")?.let { chat.open(it); ChatSupport.settle(chat) }
            delay(300)
            buildMap {
                put("received", chat.state.value.activeReceivedFrom)
                if (c.string("action") == "continue") {
                    Parity.reset(ChatSupport.after(c["after"]))
                    chat.startFromReceived().join()
                    ChatSupport.settle(chat)
                    // POST …/read is the chat-thread lot's (its body differs by `wire`).
                    put("sent", ChatSupport.sent(ChatSupport.log()).filterNot { it["path"].str().orEmpty().endsWith("/read") })
                    put("after", chat.state.value.activeReceivedFrom)
                }
                val s = chat.state.value
                put("active", s.activeId)
                put("entry", s.activeEntry?.ref)
                put("list", s.conversations.map { it.id })
                put("banner", ChatSupport.banner(chat))
            }
        } finally {
            scope.cancel()
        }
    }
}
