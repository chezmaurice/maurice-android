package eu.chezmaurice.parity

import eu.chezmaurice.core.consent.AiConsentStore
import kotlin.test.Test

class SessionConsentParity {
    @Test fun consent() = Parity.check("maurice", "session-ai-consent") { c ->
        val session = MauriceFixture.session()
        val store = AiConsentStore(session.api()!!)
        val action = c.string("action")
        if (action != "before") store.refresh()
        when {
            action == "accept" -> store.decide(accept = true)
            action == "decline" -> store.decide(accept = false)
            action?.startsWith("provider:") == true -> store.accept(action.removePrefix("provider:"))
        }
        val s = store.state.value
        mapOf(
            "requests" to SessionSupport.requests().filter { (it["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "/api/me/ai-consent" },
            "loaded" to s.loaded,
            "needsAnswer" to s.needsAnswer,
            "showsQuestion" to s.showsQuestion,
            "declined" to s.declined,
            "mayUseAI" to s.mayUseAI,
            "providers" to s.providers.map { it.id },
            "newProviders" to s.newProviders.map { it.id },
            "record" to s.record?.let { mapOf("status" to it.status, "providers" to it.providers) },
            "covers" to listOf("ollama", "scaleway", "anthropic", "mistral").associateWith { s.covers(it) },
        )
    }
}
