package eu.chezmaurice.core

import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.store.StoreAccountState
import eu.chezmaurice.core.store.storeAccount
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The household's store (`GET /api/me/store`) and the tower's account (iap-contract.md). */
class StoreTest {
    private fun household(status: HttpStatusCode, body: String) = ApiClient("https://maison.test", "t", HttpClient(MockEngine {
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }) {
        expectSuccess = false
        install(ContentNegotiation) { json(MauriceJson) }
    })

    @Test fun anAdminOfAHostedHouseholdHasAStore() = runTest {
        val store = household(HttpStatusCode.OK, """{"account":"0b5e…","url":"https://account.chezmaurice.eu"}""").storeAccount()
        assertEquals("https://account.chezmaurice.eu", store?.url)
    }

    @Test fun aMemberOrAHouseholdAtHomeHasNone() = runTest {
        assertNull(household(HttpStatusCode.NotFound, """{"error":"no_store"}""").storeAccount())
    }

    @Test fun neverTwoBillings() {
        val now = 1_800_000_000_000.0
        val viaWeb = MauriceJson.decodeFromString(StoreAccountState.serializer(),
            """{"name":"dupont","state":"live","trial_ends_at":null,"paid_through":${now + 86_400_000},"via":"mollie","plan":"monthly","products":["eu.chezmaurice.maurice.topup.5"]}""")
        assertTrue(viaWeb.isSubscribed(now))
        assertFalse(viaWeb.sellsSubscriptions("google"))
        val inTrial = MauriceJson.decodeFromString(StoreAccountState.serializer(), """{"trial_ends_at":${now + 1000},"via":null,"plan":null}""")
        assertTrue(inTrial.isInTrial(now))
        assertTrue(inTrial.sellsSubscriptions("google"))
    }
}
