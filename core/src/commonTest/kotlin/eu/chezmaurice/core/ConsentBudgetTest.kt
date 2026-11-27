package eu.chezmaurice.core

import eu.chezmaurice.core.budget.BudgetStore
import eu.chezmaurice.core.consent.AiConsentStore
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.session.baseLanguage
import eu.chezmaurice.core.store.MemoryKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsentBudgetTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        ApiClient("https://maison.test", "t", HttpClient(MockEngine(handler)) {
            expectSuccess = false
            install(ContentNegotiation) { json(MauriceJson) }
        })

    private val providers = """[{"id":"scaleway","name":"Scaleway Generative APIs","region":"FR","city":"Paris"},{"id":"anthropic","name":"Anthropic","region":"US","city":null}]"""

    @Test fun noAnswerYetAsksTheQuestionAndHoldsBackUntilThen() = runTest {
        val store = AiConsentStore(client { respond("""{"record":null,"providers":$providers,"needs_answer":true,"uncovered":[]}""", HttpStatusCode.OK, json) })
        // Before the server answers, silence is not a yes (as on iOS).
        assertFalse(store.state.value.mayUseAI)
        store.refresh()
        val s = store.state.value
        assertTrue(s.showsQuestion)
        assertFalse(s.mayUseAI)
        // Not a "no": the banner is for a member who declined, not one who has not answered.
        assertFalse(s.declined)
    }

    @Test fun aNoQuietsTheComposerAndShowsTheBanner() = runTest {
        val store = AiConsentStore(client { respond("""{"record":{"status":"declined","providers":[],"decided_at":"2026-10-29 17:23:50"},"providers":$providers,"needs_answer":false,"uncovered":[]}""", HttpStatusCode.OK, json) })
        store.refresh()
        val s = store.state.value
        assertFalse(s.showsQuestion)
        assertFalse(s.mayUseAI)
        assertTrue(s.declined)
        store.review()
        assertTrue(store.state.value.showsQuestion)
    }

    @Test fun aNewProviderIsAskedAboutAgainAndHighlighted() = runTest {
        val store = AiConsentStore(client { respond("""{"record":{"status":"accepted","providers":["scaleway"],"decided_at":"x"},"providers":$providers,"needs_answer":true,"uncovered":["anthropic"]}""", HttpStatusCode.OK, json) })
        store.refresh()
        val s = store.state.value
        assertTrue(s.showsQuestion)
        assertEquals(listOf("anthropic"), s.newProviders.map { it.id })
        assertTrue(s.covers("scaleway"))
        assertFalse(s.covers("anthropic"))
        assertTrue(s.covers("ollama"))
    }

    @Test fun acceptingSendsEveryProviderShown() = runTest {
        var sent: String? = null
        val store = AiConsentStore(client { request ->
            if (request.method == HttpMethod.Put) {
                sent = (request.body as TextContent).text
                respond("""{"record":{"status":"accepted","providers":["anthropic","scaleway"],"decided_at":"x"},"providers":$providers,"needs_answer":false,"uncovered":[]}""", HttpStatusCode.OK, json)
            } else respond("""{"record":null,"providers":$providers,"needs_answer":true,"uncovered":[]}""", HttpStatusCode.OK, json)
        })
        store.refresh()
        store.decide(accept = true)
        assertEquals("""{"status":"accepted","providers":["anthropic","scaleway"]}""", sent)
        assertTrue(store.state.value.mayUseAI)
        assertFalse(store.state.value.showsQuestion)
    }

    @Test fun aServerWithoutTheRouteAsksNothing() = runTest {
        val store = AiConsentStore(client { respond("""{"error":"not found"}""", HttpStatusCode.NotFound, json) })
        store.refresh()
        assertFalse(store.state.value.showsQuestion)
        assertTrue(store.state.value.mayUseAI)
    }

    @Test fun theBudgetAlertsOncePerThresholdAndPeriod() = runTest {
        var left = 0.25
        val kv = MemoryKeyValueStore()
        val store = BudgetStore(client { respond("""{"limited":true,"kind":"monthly","left":$left,"resets_at":"2026-12-01T00:00:00Z","period":"2026-12-01T00:00:00Z","left_eur":null}""", HttpStatusCode.OK, json) }, kv, "m")
        store.refresh()
        assertNull(store.state.value.alert)
        assertEquals(25, store.state.value.leftPercent)
        // 25 % → 8 %: told about 10 %, not 20 then 10.
        left = 0.08
        store.refresh()
        assertEquals(10, store.state.value.alert)
        store.dismissAlert()
        store.refresh()
        assertNull(store.state.value.alert)
        left = 0.04
        store.refresh()
        assertEquals(5, store.state.value.alert)
    }

    @Test fun noBudgetMeansNoRing() = runTest {
        val store = BudgetStore(client { respond("""{"limited":false,"kind":null,"left":null,"resets_at":null,"period":null,"left_eur":null}""", HttpStatusCode.OK, json) }, MemoryKeyValueStore(), "m")
        store.refresh()
        assertFalse(store.state.value.isLimited)
        assertNull(store.state.value.left)
    }

    @Test fun theDeviceLanguageIsItsBaseCode() {
        assertEquals("fr", baseLanguage("fr-BE"))
        assertEquals("pt", baseLanguage("pt_BR"))
        assertNull(baseLanguage("français"))
    }
}
