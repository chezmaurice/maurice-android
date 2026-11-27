package eu.chezmaurice.core.signup

import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.session.Invitation
import eu.chezmaurice.core.session.MauriceInvitation
import eu.chezmaurice.core.store.KeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/*
 * « Créer mon Maurice » (`SignupViews.swift`, cc5b99d): a hosted Maurice made
 * from the app, by the hosting service's tower — no store involved, so the
 * same on `main` and `foss`. The form (first name, an address checked as it
 * is typed, suggestions, e-mail, access code, terms), then the wait while the
 * tower makes it, then the household joined as a scanned invitation is: its
 * first member, its admin.
 */

/** The tower iOS builds in (`Tower.baseURL`): where hosted Maurices are made. */
const val SIGNUP_TOWER_URL = "https://account.chezmaurice.eu"

/** The zone hosted households live under: account.chezmaurice.eu → chezmaurice.eu (`Tower.zone`). */
fun towerZone(towerUrl: String = SIGNUP_TOWER_URL): String {
    val host = runCatching { Url(towerUrl.trimEnd('/')).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return "chezmaurice.eu"
    val labels = host.split('.')
    return if (labels.size > 2) labels.drop(1).joinToString(".") else host
}

/**
 * The access code a sign-up link carries (`Tower.accessCode`): what makes a
 * Maurice, not what enters one. Either a link to the tower with `?code=…`
 * (the QR code the operator shows, read by the camera or pasted), or
 * `maurice://signup?code=…` (the "Open" button of the tower's page).
 * Null for anything else.
 */
fun accessCode(text: String, towerUrl: String = SIGNUP_TOWER_URL): String? {
    val raw = text.trim()
    val url = runCatching { Url(raw) }.getOrNull() ?: return null
    val code = url.parameters["code"]?.takeIf { it.isNotEmpty() } ?: return null
    val scheme = raw.substringBefore(':', "").lowercase()
    val host = url.host.lowercase()
    return when (scheme) {
        "maurice" -> code.takeIf { host == "signup" }
        "https", "http" -> code.takeIf { host.isNotEmpty() && host == runCatching { Url(towerUrl).host.lowercase() }.getOrNull() }
        else -> null
    }
}

/**
 * The name an address has under the hosted zone (`EntrySheet.hostedName`):
 * "odile" from "odile.chezmaurice.eu", from "https://odile.chezmaurice.eu/",
 * or from "odile" alone. Null for any other address.
 */
fun hostedName(raw: String, zone: String = towerZone()): String? {
    var s = raw.trim().lowercase()
    for (scheme in listOf("https://", "http://")) if (s.startsWith(scheme)) s = s.removePrefix(scheme)
    s = s.trimEnd('/')
    s = s.removeSuffix(".$zone")
    if (s.isEmpty() || s == "localhost" || '.' in s || '/' in s || ':' in s) return null
    return s
}

/**
 * The network knows no such name (`PairingView.isNoHost`). For an address
 * just made this is not a refusal: a resolver asked before the name existed
 * keeps its "no such name" for up to half an hour (the zone's negative TTL).
 */
fun isNoHost(e: Throwable): Boolean {
    var cause: Throwable? = e
    repeat(8) {
        val c = cause ?: return false
        val name = c::class.simpleName.orEmpty()
        if (name == "UnknownHostException" || name == "UnresolvedAddressException") return true
        if (c.message?.contains("Unable to resolve host", ignoreCase = true) == true) return true
        cause = c.cause
    }
    return false
}

/** `GET /api/names/<name>`: whether the address is free; `reason` taken | reserved | shape. */
@Serializable data class NameCheck(val ok: Boolean, val name: String? = null, val reason: String? = null)

/** `GET /api/app/signup/<poll>`: unverified | waiting | … | live | failed | closed. */
@Serializable data class SignupState(
    val state: String,
    val server: String? = null,
    @SerialName("invite_code") val inviteCode: String? = null,
)

@Serializable private data class Suggestions(val suggestions: List<Suggestion> = emptyList()) {
    @Serializable data class Suggestion(val name: String, val ok: Boolean)
}
@Serializable private data class Poll(val poll: String)

/**
 * A refusal from the tower: its code (`error`, else `http_<status>`) and,
 * when it gave one, a sentence in the reader's language (`message`).
 */
class SignupRefusal(val status: Int, val code: String, val said: String?) : Exception(said ?: code)

/** JSON to and from the tower: no member token goes there (`TowerClient`). */
class SignupTower(private val http: HttpClient, baseUrl: String = SIGNUP_TOWER_URL, private val language: String? = null) {
    private val base = baseUrl.trimEnd('/')

    /** The zone hosted households live under: account.chezmaurice.eu → chezmaurice.eu. */
    val zone: String get() = towerZone(base)

    suspend fun checkName(name: String): NameCheck =
        send(HttpMethod.Get, "/api/names/" + name.encodeURLPathPart(), null, NameCheck.serializer())

    /** Free addresses to choose from, made from the first name: four at most. */
    suspend fun suggest(firstName: String): List<String> =
        send(HttpMethod.Get, "/api/suggest?q=" + firstName.encodeURLParameter(), null, Suggestions.serializer())
            .suggestions.filter { it.ok }.map { it.name }.take(4)

    /** Sends the form; answers the secret to ask after it with ([state]). */
    suspend fun signup(name: String, ownerName: String, email: String, code: String): String =
        send(HttpMethod.Post, "/api/app/signup", buildJsonObject {
            put("name", name); put("owner_name", ownerName); put("email", email); put("code", code)
            put("terms", "yes"); put("via", "app")
        }.toString(), Poll.serializer()).poll

    suspend fun state(poll: String): SignupState =
        send(HttpMethod.Get, "/api/app/signup/" + poll.encodeURLPathPart(), null, SignupState.serializer())

    private suspend fun <T> send(method: HttpMethod, path: String, body: String?, serializer: KSerializer<T>): T {
        val response = http.request("$base$path") {
            this.method = method
            header("Accept", "application/json")
            language?.let { header("Accept-Language", it) }
            if (body != null) setBody(TextContent(body, ContentType.Application.Json))
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val refusal = runCatching { MauriceJson.parseToJsonElement(text).jsonObject }.getOrNull()
            val code = refusal?.get("error")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            val said = refusal?.get("message")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            throw SignupRefusal(response.status.value, code ?: "http_${response.status.value}", said)
        }
        return MauriceJson.decodeFromString(serializer, text)
    }
}

/**
 * The sign-up under way, kept on the device: the secret the tower gave for
 * asking after it. Quitting the app between the form and the confirmation
 * mail loses nothing; it is forgotten once the Maurice is joined, or once
 * the tower no longer knows it.
 */
object SignupPoll {
    private const val KEY = "maurice.signup.poll"
    fun saved(store: KeyValueStore): String? = store.get(KEY)?.takeIf { it.isNotEmpty() }
    fun save(store: KeyValueStore, poll: String?) = store.put(KEY, poll)
}

/** How a wait ends. */
sealed interface SignupEnd {
    /** The Maurice is live: join it with this invitation. */
    data class Live(val invitation: Invitation) : SignupEnd
    /** The tower no longer knows the sign-up, or gave up on it. */
    data object Lost : SignupEnd
}

/**
 * Asks the tower every few seconds how the new Maurice is doing — the mail
 * to confirm first, then the queue, then the making — telling [onState]
 * each answer, until it is live (with an address and a code) or lost
 * (failed, closed, unknown: 404). Offline for a moment: ask again.
 */
suspend fun followSignup(
    tower: SignupTower, poll: String, onState: (String) -> Unit = {},
    /**
     * Whether the new household answers from this device. Made, the tower
     * says; ready once it answers here: a network that asked for its name
     * before it existed goes on saying "no such site" for a while, and the
     * next screen would open on that error (20 November 2026).
     */
    answers: suspend (server: String) -> Boolean = { true },
    /** Made, but its address does not answer from here yet. */
    onUnanswered: () -> Unit = {},
    pause: suspend () -> Unit = { delay(4_000) },
): SignupEnd {
    while (true) {
        try {
            val s = tower.state(poll)
            onState(s.state)
            if (s.state == "live") {
                val server = s.server?.let(MauriceInvitation::address)
                if (server != null && s.inviteCode != null) {
                    if (answers(server)) return SignupEnd.Live(Invitation(server, s.inviteCode))
                    onUnanswered()
                }
            }
            if (s.state == "failed" || s.state == "closed") return SignupEnd.Lost
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignupRefusal) {
            if (e.status == 404) return SignupEnd.Lost
        } catch (_: Throwable) {
        }
        pause()
    }
}
