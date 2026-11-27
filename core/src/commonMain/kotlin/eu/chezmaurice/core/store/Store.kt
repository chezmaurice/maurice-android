package eu.chezmaurice.core.store

import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.net.MauriceJson
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What the admin of a hosted household buys in the app: the subscription,
 * monthly or yearly, and top-ups of the household's budget — the same
 * products as the App Store's (`StoreStore.swift`). The household server only
 * says whether there is a store and which account it is; the hosting service
 * (the tower) holds the rest and believes nothing the app says: every
 * purchase is sent to it, and it reads it back from the store that took it.
 */
object StoreProducts {
    const val MONTHLY = "eu.chezmaurice.maurice.sub.monthly"
    const val YEARLY = "eu.chezmaurice.maurice.sub.yearly"
    val subscriptions = listOf(MONTHLY, YEARLY)
    val topUps = listOf("eu.chezmaurice.maurice.topup.5", "eu.chezmaurice.maurice.topup.10", "eu.chezmaurice.maurice.topup.20")
    fun isSubscription(id: String) = id in subscriptions
}

/** `GET /api/me/store` — given to the admin of a hosted household only. */
@Serializable
data class StoreAccount(
    /** The tower account's token, a UUID: passed to the store at purchase (Play's obfuscatedAccountId). */
    val account: String,
    /** The tower's base URL. */
    val url: String,
)

/** The tower's account (`GET /api/app/store/<account>`). Times are milliseconds since 1970. */
@Serializable
data class StoreAccountState(
    val name: String? = null,
    val server: String? = null,
    val state: String? = null,
    @SerialName("trial_ends_at") val trialEndsAt: Double? = null,
    @SerialName("paid_through") val paidThrough: Double? = null,
    @SerialName("paid_until") val paidUntil: Double? = null,
    /** Who bills the running subscription: "apple", "google", "mollie", or null. */
    val via: String? = null,
    /** "monthly" or "yearly"; null without a subscription. */
    val plan: String? = null,
    /** The product identifiers the app may offer this account. */
    val products: List<String>? = null,
) {
    fun isSubscribed(nowMillis: Double): Boolean = plan != null && (paidThrough ?: 0.0) > nowMillis
    fun isInTrial(nowMillis: Double): Boolean = !isSubscribed(nowMillis) && (trialEndsAt ?: 0.0) > nowMillis
    /** A subscription billed through another channel is shown, never sold over. */
    fun sellsSubscriptions(channel: String): Boolean = via == null || via == channel
}

/** The household's side: whether it has a store, for the member asking. Null: none (at home), or not its admin. */
suspend fun ApiClient.storeAccount(): StoreAccount? = try {
    get<StoreAccount>("/api/me/store")
} catch (e: ApiError) {
    if (e.status == 404 || e.status == 403) null else throw e
}

/**
 * The tower, spoken to directly: no member token goes there, and its refusals
 * carry a `message` already in the reader's language (Accept-Language).
 */
class TowerApi(private val http: HttpClient, baseUrl: String, private val language: String? = null) {
    private val base = baseUrl.trimEnd('/')

    /**
     * [store] names the channel that sells here ("google"): `products` is then
     * that store's catalogue, subscriptions only when no one else bills them.
     * Without it the tower answers as to the iOS app.
     */
    suspend fun state(account: String, store: String? = null): StoreAccountState =
        send(HttpMethod.Get, "/api/app/store/${account.encodeURLPathPart()}" + (store?.let { "?store=$it" } ?: ""), null)

    /**
     * A Play purchase, for the tower to read back from Google, credit and
     * acknowledge: answers the account as it now stands
     * (`POST /api/app/google/purchase`, agreed with the tower).
     */
    suspend fun googlePurchase(account: String, productId: String, purchaseToken: String): StoreAccountState =
        send(HttpMethod.Post, "/api/app/google/purchase", buildJsonObject {
            put("account", account); put("product_id", productId); put("purchase_token", purchaseToken)
        }.toString())

    private suspend fun send(method: HttpMethod, path: String, body: String?): StoreAccountState {
        val response = http.request("$base$path") {
            this.method = method
            language?.let { header("Accept-Language", it) }
            if (body != null) setBody(TextContent(body, ContentType.Application.Json))
        }
        if (!response.status.isSuccess()) {
            // `{error, message}`: the sentence, already in the reader's language, when there is one.
            val text = runCatching { response.bodyAsText() }.getOrDefault("")
            val body = runCatching { MauriceJson.parseToJsonElement(text).jsonObject }.getOrNull()
            val code = body?.get("error")?.jsonPrimitive?.content
            val said = body?.get("message")?.jsonPrimitive?.content
            throw TowerRefusal(response.status.value, code, said?.takeIf { it.isNotBlank() } ?: code ?: response.status.description)
        }
        return MauriceJson.decodeFromString(StoreAccountState.serializer(), response.bodyAsText())
    }
}

/**
 * A refusal from the tower: its code (`pending`, `other_account`, `unknown_purchase`,
 * `no_store`…) and its sentence, already in the reader's language.
 */
class TowerRefusal(val status: Int, val code: String?, override val message: String) : Exception(message)

/** The hosting service's address when no household named one: what iOS builds in. */
const val DEFAULT_TOWER_URL = "https://account.chezmaurice.eu"
