package eu.chezmaurice.maurice

import androidx.activity.ComponentActivity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import eu.chezmaurice.core.store.StoreProducts
import eu.chezmaurice.maurice.store.BillingChannel
import eu.chezmaurice.maurice.store.PurchaseOutcome
import eu.chezmaurice.maurice.store.StoreProduct
import eu.chezmaurice.maurice.store.StorePurchase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** The Play Store build sells through Google Play Billing. */
fun billingChannel(activity: ComponentActivity): BillingChannel = PlayBilling(activity)

/**
 * Google Play Billing for the household admin's purchases. The tower reads
 * each purchase back from Google and acknowledges it; this side only buys,
 * hands the token over, and consumes a top-up once the tower has counted it.
 */
private class PlayBilling(private val activity: ComponentActivity) : BillingChannel {
    override val name = "google"

    private var waiting: CompletableDeferred<PurchaseOutcome>? = null
    private val connecting = Mutex()
    private val details = HashMap<String, ProductDetails>()

    private val listener = PurchasesUpdatedListener { result, purchases ->
        val outcome = when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.firstOrNull()?.let { p ->
                if (p.purchaseState == Purchase.PurchaseState.PENDING) PurchaseOutcome.Pending
                else PurchaseOutcome.Done(p.asStorePurchase())
            } ?: PurchaseOutcome.Failed(null)
            BillingClient.BillingResponseCode.USER_CANCELED -> PurchaseOutcome.Cancelled
            else -> PurchaseOutcome.Failed(result.debugMessage.ifEmpty { null })
        }
        waiting?.complete(outcome)
    }

    private val client = BillingClient.newBuilder(activity)
        .setListener(listener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private suspend fun ready(): Boolean = connecting.withLock {
        if (client.isReady) return true
        suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }
                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    override suspend fun products(ids: List<String>): List<StoreProduct> {
        if (!ready()) return emptyList()
        val found = mutableListOf<ProductDetails>()
        for ((type, wanted) in ids.groupBy { if (StoreProducts.isSubscription(it)) BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP }) {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(wanted.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build() })
                .build()
            found += client.queryProductDetails(params).productDetailsList.orEmpty()
        }
        found.forEach { details[it.productId] = it }
        // In the order asked for, with the store's own price.
        return ids.mapNotNull { id -> details[id]?.let { StoreProduct(id, it.name, it.priceText() ?: return@mapNotNull null) } }
    }

    override suspend fun purchase(productId: String, account: String): PurchaseOutcome {
        if (!ready()) return PurchaseOutcome.Failed(null)
        val product = details[productId] ?: run { products(listOf(productId)); details[productId] } ?: return PurchaseOutcome.Failed(null)
        val params = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).apply {
            // A subscription is bought through its offer (its base plan).
            product.subscriptionOfferDetails?.firstOrNull()?.offerToken?.let { setOfferToken(it) }
        }.build()
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(params))
            // The tower checks that the purchase is this account's.
            .setObfuscatedAccountId(account)
            .build()
        val deferred = CompletableDeferred<PurchaseOutcome>().also { waiting = it }
        val launched = client.launchBillingFlow(activity, flow)
        if (launched.responseCode != BillingClient.BillingResponseCode.OK) return PurchaseOutcome.Failed(launched.debugMessage.ifEmpty { null })
        return deferred.await()
    }

    override suspend fun unsettled(): List<StorePurchase> =
        // Never acknowledged (the tower has not counted it), or a top-up not yet consumed.
        paid().filter { !it.isAcknowledged || it.products.any { p -> !StoreProducts.isSubscription(p) } }.map { it.asStorePurchase() }

    override suspend fun held(): List<StorePurchase> = paid().map { it.asStorePurchase() }

    private suspend fun paid(): List<Purchase> {
        if (!ready()) return emptyList()
        val all = mutableListOf<Purchase>()
        for (type in listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)) {
            all += client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build()).purchasesList
        }
        return all.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
    }

    override suspend fun settled(purchase: StorePurchase, consumable: Boolean) {
        if (!consumable || !ready()) return
        client.consumePurchase(ConsumeParams.newBuilder().setPurchaseToken(purchase.token).build())
    }

    private fun Purchase.asStorePurchase() = StorePurchase(products.first(), purchaseToken, accountIdentifiers?.obfuscatedAccountId)

    private fun ProductDetails.priceText(): String? =
        oneTimePurchaseOfferDetails?.formattedPrice
            ?: subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
}
