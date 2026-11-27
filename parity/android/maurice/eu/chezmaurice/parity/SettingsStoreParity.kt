package eu.chezmaurice.parity

import eu.chezmaurice.core.model.Role
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.store.MemoryKeyValueStore
import eu.chezmaurice.core.store.StoreAccountState
import eu.chezmaurice.core.store.StoreProducts
import eu.chezmaurice.maurice.store.BillingChannel
import eu.chezmaurice.maurice.store.PurchaseOutcome
import eu.chezmaurice.maurice.store.StoreController
import eu.chezmaurice.maurice.store.StoreError
import eu.chezmaurice.maurice.store.StoreProduct
import eu.chezmaurice.maurice.store.StorePurchase
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.time.Clock

/** Settings › Subscription (lot Settings). */
class SettingsStoreParity {
    /** Play as the simulator's App Store is to iOS here: it sells nothing. */
    private object NoProducts : BillingChannel {
        override val name = "google"
        override suspend fun products(ids: List<String>) = emptyList<StoreProduct>()
        override suspend fun purchase(productId: String, account: String) = PurchaseOutcome.Cancelled
        override suspend fun unsettled() = emptyList<StorePurchase>()
        override suspend fun held() = emptyList<StorePurchase>()
        override suspend fun settled(purchase: StorePurchase, consumable: Boolean) {}
    }

    @Test fun settingsStore() = Parity.check("maurice", "settings-store") { c ->
        when (c.string("kind")) {
            "catalogue" -> mapOf("subscriptions" to StoreProducts.subscriptions, "topUps" to StoreProducts.topUps)
            "state" -> runCatching { MauriceJson.decodeFromString(StoreAccountState.serializer(), c.text("json")) }
                .getOrNull()?.let(::state) ?: "undecodable"
            "refresh" -> {
                Parity.reset(Json.parseToJsonElement(c.get("serve").toString().replace("{{base}}", Parity.baseUrl)))
                val role = when (c.string("role")) { "admin" -> Role.Admin; "guest" -> Role.Guest; else -> Role.Standard }
                val session = MauriceFixture.session(role = role)
                val store = StoreController(session.api()!!, NoProducts, MemoryKeyValueStore(), ApiClient.defaultHttpClient(), "en")
                store.refresh()
                val s = store.state.value
                mapOf(
                    "requests" to Parity.requests(),
                    "hasStore" to s.hasStore,
                    "account" to s.account?.account,
                    "state" to s.state?.let(::state),
                    "error" to when (val e = s.error) {
                        is StoreError.Key -> SettingsSupport.strings(e.key)
                        is StoreError.Text -> e.text
                        null -> null
                    },
                )
            }
            else -> mapOf("unknown kind" to c.string("kind"))
        }
    }

    private fun state(s: StoreAccountState): Map<String, Any?> {
        val now = Clock.System.now().toEpochMilliseconds().toDouble()
        return mapOf(
            "name" to s.name, "server" to s.server, "state" to s.state, "via" to s.via, "plan" to s.plan, "products" to s.products,
            "isSubscribed" to s.isSubscribed(now), "isInTrial" to s.isInTrial(now),
        )
    }
}
