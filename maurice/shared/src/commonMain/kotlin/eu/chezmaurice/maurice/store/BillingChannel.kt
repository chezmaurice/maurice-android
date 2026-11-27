package eu.chezmaurice.maurice.store

import androidx.compose.runtime.staticCompositionLocalOf

/** A product as the store that sells it shows it: its price is the store's, never ours. */
data class StoreProduct(val id: String, val title: String, val price: String)

/** A purchase the store has taken, for the tower to read back and count. */
data class StorePurchase(val productId: String, val token: String, val account: String?)

sealed interface PurchaseOutcome {
    data class Done(val purchase: StorePurchase) : PurchaseOutcome
    /** Waits for approval (a parent, a bank's check): counted when the store says so. */
    data object Pending : PurchaseOutcome
    data object Cancelled : PurchaseOutcome
    data class Failed(val message: String?) : PurchaseOutcome
}

/**
 * The store the app sells through on this device — Google Play in the Play Store
 * build (`main`); none in the `foss` fork, on the desktop and at home, where no
 * purchase is offered at all. The app never shows another price, nor sends
 * anyone to pay elsewhere.
 *
 * The tower believes nothing the app says: every purchase goes to it
 * (`TowerApi.googlePurchase`), and only once it answered is a top-up consumed;
 * one it has not counted is handed again by [unsettled] at the next launch.
 */
interface BillingChannel {
    /** "google": what the tower calls who bills (`via`). */
    val name: String
    suspend fun products(ids: List<String>): List<StoreProduct>
    /** [account] is the tower account's token, carried by the store in the purchase. */
    suspend fun purchase(productId: String, account: String): PurchaseOutcome
    /** Purchases the store holds that the tower may not have counted yet. */
    suspend fun unsettled(): List<StorePurchase>
    /** Every purchase the store holds for this store account: « Restaurer les achats ». */
    suspend fun held(): List<StorePurchase>
    /** The tower counted it: a top-up can be bought again. */
    suspend fun settled(purchase: StorePurchase, consumable: Boolean)
}

/** Null where nothing is sold. */
val LocalBilling = staticCompositionLocalOf<BillingChannel?> { null }
