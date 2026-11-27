package eu.chezmaurice.maurice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.store.MemoryKeyValueStore
import eu.chezmaurice.core.store.StoreProducts
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.maurice.store.BillingChannel
import eu.chezmaurice.maurice.store.PurchaseOutcome
import eu.chezmaurice.maurice.store.StoreController
import eu.chezmaurice.maurice.store.StoreProduct
import eu.chezmaurice.maurice.store.StorePurchase
import eu.chezmaurice.maurice.store.SubscriptionPane
import eu.chezmaurice.ui.hexColor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The admin's purchases: the household's store, the tower and Play, all three stood in for. */
class SubscriptionTest {
    private val account = "0b5e6a1c-0000-4000-8000-000000000001"
    private val far = 4_102_444_800_000L // 2100
    private val sent = mutableListOf<String>()
    /** What the tower now says of the account, once it counted a purchase. */
    private var counted: String? = null
    /** The tower's answer while Google has not been paid: 409 pending. */
    private var unpaid = false

    private fun products() = (StoreProducts.subscriptions + StoreProducts.topUps).joinToString(",", "[", "]") { "\"$it\"" }

    private fun http(paidThrough: Long?, via: String?) = HttpClient(MockEngine { request ->
        val path = request.url.encodedPath
        val body = when {
            path == "/api/me/store" -> """{"account":"$account","url":"https://tower.test"}"""
            path == "/api/app/google/purchase" && unpaid -> return@MockEngine respond(
                """{"error":"pending","message":"Le paiement attend chez Google."}""", HttpStatusCode.Conflict,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
            path == "/api/app/google/purchase" -> {
                sent += (request.body as TextContent).text
                """{"state":"active","plan":"monthly","via":"google","paid_through":$far,"products":${products()}}""".also { counted = it }
            }
            path.startsWith("/api/app/store/") -> counted ?: run {
                check(request.url.parameters["store"] == "google") { "the Play build asks for Play's catalogue" }
                val plan = if (paidThrough != null) "\"yearly\"" else "null"
                """{"state":"trial","trial_ends_at":$far,"paid_through":$paidThrough,"plan":$plan,"via":${via?.let { "\"$it\"" }},"products":${products()}}"""
            }
            else -> "{}"
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }) {
        expectSuccess = false
        install(ContentNegotiation) { json(MauriceJson) }
    }

    private class FakePlay(private val account: String) : BillingChannel {
        override val name = "google"
        val consumed = mutableListOf<String>()
        private val prices = mapOf(
            StoreProducts.MONTHLY to "14,99 €", StoreProducts.YEARLY to "149,99 €",
            "eu.chezmaurice.maurice.topup.5" to "5,99 €", "eu.chezmaurice.maurice.topup.10" to "11,99 €", "eu.chezmaurice.maurice.topup.20" to "22,99 €",
        )
        override suspend fun products(ids: List<String>) = ids.map { StoreProduct(it, it, prices.getValue(it)) }
        override suspend fun purchase(productId: String, account: String) = PurchaseOutcome.Done(StorePurchase(productId, "tok-$productId", account))
        override suspend fun unsettled() = listOf(StorePurchase("eu.chezmaurice.maurice.topup.10", "tok-left", account))
        override suspend fun held() = unsettled()
        override suspend fun settled(purchase: StorePurchase, consumable: Boolean) { if (consumable) consumed += purchase.token }
    }

    private fun controller(play: BillingChannel, paidThrough: Long? = null, via: String? = null): StoreController {
        val http = http(paidThrough, via)
        return StoreController(ApiClient("https://maison.test", "t", http), play, MemoryKeyValueStore(), http, "fr")
    }

    @Test fun aTopUpIsSentToTheTowerThenConsumed() = runBlocking {
        val play = FakePlay(account)
        val store = controller(play)
        store.refresh()
        assertTrue(store.state.value.hasStore)
        assertTrue(store.state.value.offersSubscriptions("google"))
        store.purchase("eu.chezmaurice.maurice.topup.5")
        assertTrue(sent.single().contains("\"purchase_token\":\"tok-eu.chezmaurice.maurice.topup.5\""))
        assertTrue(sent.single().contains(account))
        assertEquals(listOf("tok-eu.chezmaurice.maurice.topup.5"), play.consumed)
        assertNull(store.state.value.busy)
    }

    @Test fun aSubscriptionIsNeverConsumed() = runBlocking {
        val play = FakePlay(account)
        val store = controller(play)
        store.refresh()
        store.purchase(StoreProducts.MONTHLY)
        assertTrue(play.consumed.isEmpty())
        assertEquals("google", store.state.value.state?.via)
    }

    @Test fun aPaymentPendingAtGoogleWaitsAndIsNotConsumed() = runBlocking {
        val play = FakePlay(account)
        val store = controller(play)
        store.refresh()
        unpaid = true
        store.purchase("eu.chezmaurice.maurice.topup.20")
        assertTrue(store.state.value.pending)
        assertNull(store.state.value.error)
        assertTrue(play.consumed.isEmpty())
    }

    @Test fun whatTheTowerDidNotCountIsSentAgain() = runBlocking {
        val play = FakePlay(account)
        controller(play).settleLeftovers()
        assertEquals(listOf("tok-left"), play.consumed)
    }

    @Test fun aSubscriptionBilledByAppleIsNotSoldOver() = runBlocking {
        val store = controller(FakePlay(account), paidThrough = far, via = "apple")
        store.refresh()
        assertTrue(store.state.value.isSubscribed)
        assertFalse(store.state.value.offersSubscriptions("google"))
        assertEquals(3, store.state.value.topUps.size)
    }

    @Test fun pane() {
        for ((name, via, paid) in listOf(Triple("trial", null, null), Triple("google", "google", far))) {
            val play = FakePlay(account)
            val store = controller(play, paid, via)
            runBlocking { store.refresh() }
            for (look in listOf(Looks.day, Looks.night)) snapshot("subscription-$name", Frames.tallPhone, look) {
                CompositionLocalProvider(LocalAccent provides hexColor("#3d6b4f")) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) { SubscriptionPane(store, play, "fr") }
                    }
                }
            }
        }
    }
}
