package eu.chezmaurice.parity

import eu.chezmaurice.core.chat.ChatApi
import eu.chezmaurice.core.chat.ChatController
import kotlin.test.Test

class DomainsBindingParity {
    @Test fun domainsBinding() = Parity.check("maurice", "domains-binding") { c ->
        val s = DomainsSupport.Scoped()
        val chat = ChatController(ChatApi(MauriceFixture.session().api()!!), s.scope, "u1")
        chat.loadConversations().join()
        val ok = chat.setDomain(c.string("domain"), c.string("conversation").orEmpty())
        s.settle(5_000)
        val st = chat.state.value
        val out = mapOf(
            "requests" to DomainsSupport.requests { it.endsWith("/maurice") },
            "ok" to ok,
            "rows" to st.conversations.map { mapOf("id" to it.id, "domain" to it.mauriceId, "boundBy" to it.mauriceBoundBy) },
            "error" to (st.error != null),
        )
        s.cancel()
        out
    }
}
