package eu.chezmaurice.maurice.store

import androidx.compose.runtime.staticCompositionLocalOf
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.store.DEFAULT_TOWER_URL
import eu.chezmaurice.core.store.KeyValueStore
import eu.chezmaurice.core.store.StoreAccount
import eu.chezmaurice.core.store.StoreAccountState
import eu.chezmaurice.core.store.StoreProducts
import eu.chezmaurice.core.store.TowerApi
import eu.chezmaurice.core.store.TowerRefusal
import eu.chezmaurice.core.store.storeAccount
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** What the Abonnement pane says went wrong: a key of ours, or the tower's own sentence. */
sealed interface StoreError {
    data class Key(val key: String) : StoreError
    data class Text(val text: String) : StoreError
}

data class StoreUiState(
    /** Null: no store for this member (at home, or not the admin) — nothing shown. */
    val account: StoreAccount? = null,
    val state: StoreAccountState? = null,
    val products: Map<String, StoreProduct> = emptyMap(),
    /** The product being bought, or "restore". */
    val busy: String? = null,
    val pending: Boolean = false,
    val error: StoreError? = null,
) {
    val hasStore: Boolean get() = account != null
    private val now: Double get() = Clock.System.now().toEpochMilliseconds().toDouble()
    val isSubscribed: Boolean get() = state?.isSubscribed(now) == true
    val isInTrial: Boolean get() = state?.isInTrial(now) == true

    private fun offered(ids: List<String>): List<StoreProduct> {
        val allowed = state?.products.orEmpty().toSet()
        return ids.filter { it in allowed }.mapNotNull { products[it] }
    }
    val subscriptions: List<StoreProduct> get() = offered(StoreProducts.subscriptions)
    val topUps: List<StoreProduct> get() = offered(StoreProducts.topUps)

    /** Plans are sold here unless another channel (Apple, the web) bills the subscription. */
    fun offersSubscriptions(channel: String): Boolean =
        state?.sellsSubscriptions(channel) == true && subscriptions.isNotEmpty()

    /** The product of the running subscription, for its check mark. */
    val currentProduct: String? get() = when {
        !isSubscribed -> null
        state?.plan == "yearly" -> StoreProducts.YEARLY
        else -> StoreProducts.MONTHLY
    }
}

/**
 * The admin's purchases, as `StoreStore.swift` makes them: ask the household
 * whether it has a store, the tower how the account stands, the store what it
 * sells. Every purchase goes to the tower, which reads it back from Google;
 * only after its answer is a top-up consumed. One the tower did not count
 * (offline, the tower down) stays with Play and is sent again by [settleLeftovers].
 */
class StoreController(
    private val api: ApiClient,
    private val billing: BillingChannel,
    private val keys: KeyValueStore,
    private val http: HttpClient,
    private val language: String?,
) {
    private val _state = MutableStateFlow(StoreUiState())
    val state: StateFlow<StoreUiState> = _state.asStateFlow()
    private val sending = Mutex()

    suspend fun refresh() {
        val found = runCatching { api.storeAccount() }.getOrNull()
        if (found == null) {
            _state.update { StoreUiState() }
            return
        }
        _state.update { if (it.account != found) StoreUiState(account = found) else it.copy(account = found) }
        keys.put(TOWER_KEY, found.url)
        loadState()
        loadProducts()
    }

    private suspend fun loadState() {
        val account = _state.value.account ?: return
        runCatching { tower(account.url).state(account.account, billing.name) }
            .onSuccess { s -> _state.update { it.copy(state = s) } }
            .onFailure { _state.update { it.copy(error = StoreError.Key("store.error.unreachable")) } }
    }

    private suspend fun loadProducts() {
        if (_state.value.products.isNotEmpty()) return
        val found = runCatching { billing.products(StoreProducts.subscriptions + StoreProducts.topUps) }.getOrDefault(emptyList())
        if (found.isNotEmpty()) _state.update { it.copy(products = found.associateBy(StoreProduct::id)) }
    }

    /** Buy [productId]; [afterwards] refreshes what depends on it (the budget). */
    suspend fun purchase(productId: String, afterwards: suspend () -> Unit = {}) {
        val now = _state.value
        val account = now.account ?: return
        if (now.busy != null) return
        _state.update { it.copy(busy = productId, error = null, pending = false) }
        try {
            when (val outcome = billing.purchase(productId, account.account)) {
                is PurchaseOutcome.Done -> {
                    when (send(outcome.purchase)) {
                        Sent.Counted -> {}
                        // Google has not been paid yet: counted when it is, at a later launch.
                        Sent.Pending -> _state.update { it.copy(pending = true) }
                        Sent.NotCounted -> _state.update { it.copy(error = StoreError.Key("store.error.not_counted")) }
                    }
                    loadState()
                    afterwards()
                }
                PurchaseOutcome.Pending -> _state.update { it.copy(pending = true) }
                PurchaseOutcome.Cancelled -> {}
                is PurchaseOutcome.Failed -> _state.update { it.copy(error = outcome.message?.let(StoreError::Text) ?: StoreError.Key("store.error.unverified.play")) }
            }
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    /** « Restaurer les achats »: everything Play holds for this Google account goes to the tower again. */
    suspend fun restore() {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = RESTORE, error = null) }
        try {
            runCatching { billing.held() }.getOrDefault(emptyList()).forEach { send(it) }
            loadState()
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    /** At launch: purchases Play holds that the tower has not counted yet — a pending one approved since, a lost answer. */
    suspend fun settleLeftovers() {
        runCatching { billing.unsettled() }.getOrDefault(emptyList()).forEach { send(it) }
    }

    /**
     * One purchase to the tower; settled with Play once it answered. The
     * account the purchase names, at the tower last given — not necessarily
     * the household in view.
     */
    private suspend fun send(purchase: StorePurchase): Sent = sending.withLock {
        val owner = purchase.account ?: return Sent.NotCounted
        val current = _state.value.account
        val url = current?.url ?: keys.get(TOWER_KEY) ?: DEFAULT_TOWER_URL
        val answer = try {
            tower(url).googlePurchase(owner, purchase.productId, purchase.token)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            println("[store] purchase ${purchase.productId} not counted: ${e.message}")
            return if ((e as? TowerRefusal)?.code == "pending") Sent.Pending else Sent.NotCounted
        }
        runCatching { billing.settled(purchase, consumable = !StoreProducts.isSubscription(purchase.productId)) }
        if (current?.account.equals(owner, ignoreCase = true)) _state.update { it.copy(state = answer) }
        Sent.Counted
    }

    private enum class Sent { Counted, Pending, NotCounted }

    private fun tower(url: String) = TowerApi(http, url, language)

    companion object {
        const val RESTORE = "restore"
        private const val TOWER_KEY = "maurice.store.url"
    }
}

/** Null where nothing is sold: the `foss` build, the desktop, a member who is not the admin. */
val LocalStore = staticCompositionLocalOf<StoreController?> { null }
