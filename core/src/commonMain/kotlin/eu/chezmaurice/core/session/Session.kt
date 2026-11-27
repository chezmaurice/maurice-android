package eu.chezmaurice.core.session

import eu.chezmaurice.core.model.EnrollResponse
import eu.chezmaurice.core.model.Health
import eu.chezmaurice.core.model.Household
import eu.chezmaurice.core.model.LoginResponse
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.model.Role
import eu.chezmaurice.core.model.SetupResponse
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.store.KeyValueStore
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

@kotlinx.serialization.Serializable
private data class McpToken(val rawToken: String)

@kotlinx.serialization.Serializable
private data class ServerPrefs(
    val palette: String? = null,
    val locale: String? = null,
    @kotlinx.serialization.SerialName("device_locale") val deviceLocale: String? = null,
)

/**
 * What this app runs on, as POST /api/users/me/device takes it. Each
 * launcher fills it in; Carnet passes none, as its iOS app says nothing.
 */
data class DeviceInfo(
    /** "Android", "Windows" or "Linux". */
    val platform: String,
    /** The maker's model ("Pixel 8", "SM-S921B"), never a name the member gave. */
    val model: String?,
    val osVersion: String?,
    /** "maurice". */
    val app: String,
    val appVersion: String?,
)

/**
 * A member's own look and language on this device. [palette] and [locale]
 * also live on the server (the chat's language follows [locale]); the web
 * garden's theme is local, as on iOS.
 */
data class Prefs(
    /** "auto", or one of `Palettes` (cream, studio…). */
    val palette: String = "auto",
    /** en fr it de es pt nl; null follows the system. */
    val locale: String? = null,
    val webTheme: String = "manuscript",
)

/** A language as the server keeps it: its base code, lowercased ("fr-BE" → "fr"); null when it is not one. */
internal fun baseLanguage(tag: String): String? =
    tag.trim().split('-', '_').first().lowercase().takeIf { Regex("^[a-z]{2,3}$").matches(it) }

/** Percent-encode all but `A–Z a–z 0–9 - . _ ~`, as the iOS app does for `to`. */
internal fun encodeQueryValue(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt().toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~") append(c)
        else append('%').append((byte.toInt() and 0xff).toString(16).uppercase().padStart(2, '0'))
    }
}

data class SessionState(
    val households: List<Household> = emptyList(),
    val currentId: String? = null,
    /** A household whose session the server just revoked (401): the picker says so. */
    val endedHouseholdId: String? = null,
) {
    val current: Household? get() = households.firstOrNull { it.id == currentId }
    /** Signed in: a current household with an active member. */
    val activeMember: Member? get() = current?.activeMember
}

/**
 * An enrolment waiting for its PIN: the member exists on the server, but the
 * app stores them only once they have chosen one (as on iOS).
 */
data class PendingEnrollment(val householdId: String, val member: Member, val token: String)

sealed interface EnrollOutcome {
    /** An open invitation: ask for a name and a colour, then call again. */
    data class NeedsProfile(val household: String?) : EnrollOutcome
    data class NeedsPin(val pending: PendingEnrollment) : EnrollOutcome
    data class SignedIn(val member: Member) : EnrollOutcome
}

/** Where [Session.deviceId] is kept: not under an app's prefix, the phone's own. */
private const val DEVICE_ID = "device.id"

/**
 * The households paired on this device, their members, and one token per
 * member. Exactly one household is current and, once signed in, one of its
 * members is active: every request goes there with that member's token.
 *
 * Shared by both apps; [prefix] keeps their keys apart ("maurice", "carnet").
 */
class Session(
    /** Also where the stores beside the session keep what they remember (the budget's alerts). */
    val store: KeyValueStore,
    private val prefix: String,
    private val http: HttpClient = ApiClient.defaultHttpClient(),
    private val device: DeviceInfo? = null,
    /** DNS TXT, through the system's resolver; a stand-in in tests. */
    private val txtLookup: suspend (String) -> List<String> = ::txtRecords,
    /** The households the other app of the pair (Maurice, Carnet) knows: null where there is none. */
    private val shared: PairingsChannel? = null,
    /** Milliseconds; a stand-in in tests. */
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _prefs = MutableStateFlow(localPrefs())
    /** The active member's preferences; the defaults when no one is signed in. */
    val prefs: StateFlow<Prefs> = _prefs.asStateFlow()

    /**
     * Called with (household id, member id) when a member leaves this device:
     * their account deleted, their session refused (401), their household
     * unpaired. The app erases what it kept for them. Switching member is not
     * leaving: the token stays for a quick switch back.
     */
    var onMemberGone: ((householdId: String, memberId: String) -> Unit)? = null

    // ── Clients ────────────────────────────────────────────

    /** The current household as its active member, or null when signed out. */
    fun api(): ApiClient? {
        val household = _state.value.current ?: return null
        val memberId = household.activeMemberId ?: return null
        val token = token(household.id, memberId) ?: return null
        return ApiClient(household.serverUrl, token, http) { onUnauthorized(household.id, memberId) }
    }

    /** Any household as one of its members still signed in on this device (Carnet's outbox), or null. */
    fun apiAs(householdId: String, memberId: String): ApiClient? {
        val household = household(householdId) ?: return null
        val token = token(householdId, memberId) ?: return null
        return ApiClient(household.serverUrl, token, http) { onUnauthorized(householdId, memberId) }
    }

    private fun anonymous(serverUrl: String) = ApiClient(serverUrl, null, http)
    private fun withToken(serverUrl: String, token: String) = ApiClient(serverUrl, token, http)

    // ── Pairing ────────────────────────────────────────────

    /** `GET /api/health`: is there a Maurice server at this address? */
    suspend fun health(serverUrl: String): Health {
        val health: Health = anonymous(serverUrl).get("/api/health", authenticated = false)
        if (health.status != "ok") throw ApiError(0, "not_a_maurice_server")
        return health
    }

    /** Typed address → household (re-selected if already paired). No member yet. */
    suspend fun connect(rawAddress: String, normalize: (String) -> String? = ::normalizeServerUrl): Household {
        val url = normalize(rawAddress) ?: throw ApiError(0, "invalid_address")
        return pair(url, health(url))
    }

    /**
     * An invitation pairs its server (or re-selects it), then the picker
     * enrols with the code. Every household is refreshed then, as on iOS:
     * the new one takes its name, colour and icon, the others their badge.
     */
    suspend fun receive(invitation: Invitation): Household {
        // The same invitation handed over again just after it let someone in
        // (a link read twice, the wait and the picker both passing it on): not
        // used again, or the server would count a second device (22 November 2026).
        if (justServed(invitation.serverUrl, invitation.code)) {
            _state.value.households.firstOrNull { it.serverUrl == invitation.serverUrl }?.let { return it }
        }
        val household = pair(invitation.serverUrl, null)
        // Kept until it has served (Household.invite).
        mutate { s -> s.copy(households = s.households.map { if (it.id == household.id) it.copy(invite = invitation.code) else it }) }
        setActive(household.id, null)
        refreshHouseholds()
        return household(household.id) ?: household
    }

    /** The invitation the current household is still holding (`keptInvite`). */
    val keptInvite: String? get() = _state.value.current?.invite

    /** The last invitation that let someone in here: its address, its code, when. Process-local. */
    private var served: Triple<String, String, Long>? = null

    /**
     * Whether this code let someone into this household less than a minute
     * ago (`SessionStore.receive`, 22 November 2026): an invitation handed over
     * again then is not used a second time.
     */
    fun justServed(serverUrl: String, code: String): Boolean {
        val (url, bare, at) = served ?: return false
        return url == serverUrl && sameCode(code, bare) && now() - at < 60_000
    }

    /**
     * This phone, the same for the Maurice app and Carnet (it travels with the
     * shared pairings), sent with every sign-in as `device_id`: the server
     * then knows a device it already has a session for, and does not tell the
     * member that a new one came in.
     */
    val deviceId: String
        get() = store.get(DEVICE_ID) ?: newId().lowercase().also { store.put(DEVICE_ID, it) }

    /** The kept invitation has served, or the household refused it for good. */
    fun forgetInvite() {
        val current = _state.value.current ?: return
        if (current.invite == null) return
        mutate { s -> s.copy(households = s.households.map { if (it.id == current.id) it.copy(invite = null) else it }) }
    }

    private fun pair(serverUrl: String, health: Health?): Household {
        val existing = _state.value.households.firstOrNull { it.serverUrl == serverUrl }
        val name = health?.household?.takeIf { it.isNotBlank() }
            ?: existing?.name
            ?: runCatching { Url(serverUrl).host }.getOrDefault(serverUrl)
        val household = (existing ?: Household(id = newId(), serverUrl = serverUrl, name = name)).copy(
            name = name,
            color = health?.householdColor ?: existing?.color,
            icon = health?.householdIcon ?: existing?.icon,
            // Pinned once; a different key later is not this household.
            key = existing?.key ?: health?.identityKey,
        )
        mutate { state ->
            val others = state.households.filterNot { it.id == household.id }
            state.copy(households = others + household, currentId = household.id)
        }
        return household
    }

    /**
     * Every paired household's name, colour and icon from `/api/health`, its
     * identity key pinned the first time it is given — and, for one silent at
     * its address whose key this device holds, whether it moved
     * (HouseholdMove): then the same entry takes the new address, its id,
     * members, tokens and push tag unchanged. Quiet on any failure; at
     * launch and when the app comes back.
     */
    suspend fun refreshHouseholds() = coroutineScope {
        val updates = _state.value.households.map { h ->
            async {
                var url = h.serverUrl
                var health = fetchHealth(url)
                var movedTo: String? = null
                if (health == null && h.key != null) {
                    HouseholdMove.follow(url, h.key, http, txtLookup)?.let { target ->
                        movedTo = target
                        url = target
                        health = fetchHealth(target)
                    }
                }
                HouseholdUpdate(h.id, health, movedTo, if (prefix == "maurice") unread(h, url) else null)
            }
        }.awaitAll()
        for ((id, health, found, unread) in updates) {
            val before = household(id) ?: continue
            // Already paired there under another entry: leave both as they are.
            val movedTo = found?.takeIf { target -> _state.value.households.none { it.id != id && it.serverUrl.trimEnd('/') == target } }
            if (found != null && movedTo == null) continue
            if (movedTo != null) {
                // What was kept under the old name follows it.
                if (store.get("$prefix.connect_disclosure.${host(before.serverUrl)}") != null) {
                    store.put("$prefix.connect_disclosure.${host(movedTo)}", "1")
                }
            }
            updateHousehold(id) { h ->
                var next = if (movedTo != null) h.copy(serverUrl = movedTo) else h
                // The household's own look: a colour or icon it no longer gives is gone (as on iOS).
                if (health != null) next = next.copy(
                    name = health.household?.trim()?.takeIf { it.isNotEmpty() } ?: next.name,
                    color = health.householdColor,
                    icon = health.householdIcon,
                    key = next.key ?: health.identityKey,
                )
                if (unread != null) next = next.copy(unread = unread)
                next
            }
        }
    }

    private data class HouseholdUpdate(val id: String, val health: Health?, val movedTo: String?, val unread: Int?)

    /**
     * `GET /api/health` as a refresh reads it: whatever its status says, the
     * name, colour and icon are the household's (only pairing insists on "ok").
     */
    private suspend fun fetchHealth(serverUrl: String): Health? =
        withTimeoutOrNull(8_000) { runCatching { anonymous(serverUrl).get<Health>("/api/health", authenticated = false) }.getOrNull() }

    /**
     * The active member's unread count in [household] (`GET /api/users/me/unread`),
     * for the switcher's badge; null when signed out or on any failure. A 401
     * ends that member's session, as any refused request does.
     */
    private suspend fun unread(household: Household, serverUrl: String): Int? {
        val memberId = household.activeMemberId ?: return null
        val token = token(household.id, memberId) ?: return null
        val client = ApiClient(serverUrl, token, http) { onUnauthorized(household.id, memberId) }
        return withTimeoutOrNull(8_000) { runCatching { client.get<UnreadCount>("/api/users/me/unread").unread }.getOrNull() }
    }

    @kotlinx.serialization.Serializable
    private data class UnreadCount(val unread: Int)

    // ── Signing in ─────────────────────────────────────────

    suspend fun enroll(code: String, displayName: String? = null, avatarColor: String? = null): EnrollOutcome {
        val household = requireCurrent()
        val body = buildJsonObject {
            put("code", code.trim())
            put("device_id", deviceId)
            if (displayName != null) put("display_name", displayName.trim())
            if (avatarColor != null) put("avatar_color", avatarColor)
        }
        val response: EnrollResponse = try {
            anonymous(household.serverUrl).post("/api/auth/enroll", body, authenticated = false)
        } catch (e: ApiError) {
            // Refused for good: stop offering it.
            if (e.status == 401 && sameCode(code, household.invite)) forgetInvite()
            throw e
        }
        if (response.needsProfile) return EnrollOutcome.NeedsProfile(response.household)
        val token = response.token ?: throw ApiError(0, "enroll_without_token")
        val me = me(household.serverUrl, token)
        // It has served: nothing left to offer again, and not to be used again if handed over once more.
        served = Triple(household.serverUrl, code, now())
        forgetInvite()
        return if (response.needsPin) {
            val pending = PendingEnrollment(household.id, me, token)
            // Kept until the PIN is chosen: the server already has the member and
            // the invitation is spent, so an app killed now must resume here.
            store.put("$prefix.pending.${household.id}", MauriceJson.encodeToString(Member.serializer(), me))
            store.putSecret("$prefix.pending-token.${household.id}", token)
            EnrollOutcome.NeedsPin(pending)
        } else {
            signIn(household.id, me, token)
            EnrollOutcome.SignedIn(me)
        }
    }

    /** `POST /api/auth/set-pin`, 4–6 digits, then the member is stored and active. */
    suspend fun setPin(pending: PendingEnrollment, pin: String) {
        val household = household(pending.householdId) ?: throw ApiError(0, "household_gone")
        withToken(household.serverUrl, pending.token)
            .send(HttpMethod.Post, "/api/auth/set-pin", buildJsonObject { put("pin", pin) })
        signIn(household.id, pending.member.copy(hasPin = true), pending.token)
        store.put("$prefix.pending.${household.id}", null)
        store.putSecret("$prefix.pending-token.${household.id}", null)
        refreshMembers()
    }

    /** An enrolment interrupted before its PIN (the app was closed), if any. */
    fun pendingEnrollment(householdId: String): PendingEnrollment? {
        val member = store.get("$prefix.pending.$householdId")?.let {
            runCatching { MauriceJson.decodeFromString(Member.serializer(), it) }.getOrNull()
        } ?: return null
        val token = store.getSecret("$prefix.pending-token.$householdId") ?: return null
        return PendingEnrollment(householdId, member, token)
    }

    /** Tapping a member who has a PIN: always asked, even with a cached token. */
    suspend fun loginWithPin(member: Member, pin: String) {
        val household = requireCurrent()
        val body = buildJsonObject { put("user_id", member.id); put("pin", pin); put("device_id", deviceId) }
        val login: LoginResponse = anonymous(household.serverUrl).post("/api/auth/login", body, authenticated = false)
        signIn(household.id, me(household.serverUrl, login.token), login.token)
    }

    /**
     * Tapping a member without a PIN: a local switch when the device holds
     * their token. Without one there is no way in but an invitation code —
     * the server refuses a PIN-less login. Returns false then.
     */
    fun switchTo(member: Member): Boolean {
        val household = _state.value.current ?: return false
        if (member.hasPin || token(household.id, member.id) == null) return false
        setActive(household.id, member.id)
        return true
    }

    suspend fun adminLogin(username: String, password: String) {
        val household = requireCurrent()
        val body = buildJsonObject { put("username", username.trim()); put("password", password); put("device_id", deviceId) }
        val login: LoginResponse = anonymous(household.serverUrl).post("/api/auth/login", body, authenticated = false)
        signIn(household.id, me(household.serverUrl, login.token), login.token)
        refreshMembers()
    }

    /** First run of a fresh server: creates its admin. */
    suspend fun setupAdmin(username: String, password: String, displayName: String) {
        val household = requireCurrent()
        val body = buildJsonObject {
            put("username", username.trim()); put("password", password); put("display_name", displayName.trim()); put("device_id", deviceId)
        }
        val setup: SetupResponse = anonymous(household.serverUrl).post("/api/auth/setup", body, authenticated = false)
        signIn(household.id, me(household.serverUrl, setup.token), setup.token)
    }

    /**
     * `GET /api/users`, with any token this device holds for the household:
     * the member grid needs one. Non-admins get no usernames, so the members
     * already known keep theirs.
     */
    suspend fun refreshMembers() {
        val household = _state.value.current ?: return
        val token = household.activeMemberId?.let { token(household.id, it) }
            ?: household.members.firstNotNullOfOrNull { token(household.id, it.id) }
            ?: return
        val roster: List<Member> = withToken(household.serverUrl, token).get("/api/users")
        val known = household.members.associateBy { it.id }
        val merged = roster.map { it.copy(username = it.username ?: known[it.id]?.username) }
        updateHousehold(household.id) { it.copy(members = merged) }
    }

    private suspend fun me(serverUrl: String, token: String): Member =
        withToken(serverUrl, token).get("/api/users/me")

    private fun signIn(householdId: String, member: Member, token: String) {
        putToken(householdId, member.id, token)
        _state.update { if (it.endedHouseholdId == householdId) it.copy(endedHouseholdId = null) else it }
        updateHousehold(householdId) { h ->
            h.copy(members = h.members.filterNot { it.id == member.id } + member, activeMemberId = member.id)
        }
        mutate { it.copy(currentId = householdId) }
    }

    // ── Leaving ────────────────────────────────────────────

    /** Back to the member picker; the token stays for a quick switch back. */
    fun switchMember() {
        val household = _state.value.current ?: return
        setActive(household.id, null)
    }

    fun selectHousehold(id: String) = mutate { it.copy(currentId = id) }

    /** The admin renamed this Maurice (Settings → Members): the switcher follows. */
    fun renameHousehold(id: String, name: String) = updateHousehold(id) { it.copy(name = name) }

    /**
     * "Sign out": the active member only. Their session is closed on the
     * server (best effort), their token dropped, what the app kept for them
     * erased; the household stays on the device, back at the member picker.
     * Not "Forget this household" ([disconnect]).
     */
    suspend fun signOut() {
        val household = _state.value.current ?: return
        val memberId = household.activeMemberId ?: return
        token(household.id, memberId)?.let { token ->
            runCatching { withToken(household.serverUrl, token).send(HttpMethod.Post, "/api/auth/logout", buildJsonObject {}) }
        }
        putToken(household.id, memberId, null)
        onMemberGone?.invoke(household.id, memberId)
        setActive(household.id, null)
    }

    /** The member's account is gone (deleted from My data): forget them on this device. */
    fun disconnectMember(householdId: String, memberId: String) {
        putToken(householdId, memberId, null)
        onMemberGone?.invoke(householdId, memberId)
        updateHousehold(householdId) { h ->
            h.copy(members = h.members.filterNot { it.id == memberId }, activeMemberId = if (h.activeMemberId == memberId) null else h.activeMemberId)
        }
    }

    /** Forget a household and its tokens; each session is closed on the server, best effort. */
    suspend fun disconnect(householdId: String) {
        val household = household(householdId) ?: return
        for (member in household.members) {
            onMemberGone?.invoke(householdId, member.id)
            val token = token(householdId, member.id) ?: continue
            runCatching { withToken(household.serverUrl, token).send(HttpMethod.Post, "/api/auth/logout", buildJsonObject {}) }
            putToken(householdId, member.id, null)
        }
        store.put("$prefix.pending.$householdId", null)
        store.putSecret("$prefix.pending-token.$householdId", null)
        mutate { state ->
            val rest = state.households.filterNot { it.id == householdId }
            state.copy(households = rest, currentId = if (state.currentId == householdId) rest.firstOrNull()?.id else state.currentId)
        }
    }

    /** Sessions never expire: a 401 means revoked. Drop the token, back to the picker. */
    private fun onUnauthorized(householdId: String, memberId: String) {
        putToken(householdId, memberId, null)
        onMemberGone?.invoke(householdId, memberId)
        updateHousehold(householdId) { if (it.activeMemberId == memberId) it.copy(activeMemberId = null) else it }
        _state.update { it.copy(endedHouseholdId = householdId) }
    }

    /** The picker has told the member their session ended. */
    fun acknowledgeEndedSession() = _state.update { it.copy(endedHouseholdId = null) }

    // ── Push ───────────────────────────────────────────────

    /**
     * Declare this device's push address to every paired household, as its
     * active member (`POST /api/users/me/device-token`), as iOS does. On
     * Android the address is a Web Push subscription from UnifiedPush: the
     * endpoint URL and the keys the server encrypts to. `household_tag` lets
     * a notification's tap find its household.
     */
    suspend fun registerPush(endpoint: String, p256dh: String, auth: String, platform: String = "android") {
        for (household in _state.value.households) {
            val memberId = household.activeMemberId ?: continue
            val token = token(household.id, memberId) ?: continue
            runCatching {
                withToken(household.serverUrl, token).send(HttpMethod.Post, "/api/users/me/device-token", buildJsonObject {
                    put("token", endpoint); put("platform", platform); put("household_tag", household.id)
                    put("keys", buildJsonObject { put("p256dh", p256dh); put("auth", auth) })
                })
            }
        }
    }

    /** Forget this device's push address everywhere (the distributor dropped it). */
    suspend fun unregisterPush(endpoint: String) {
        for (household in _state.value.households) {
            val memberId = household.activeMemberId ?: continue
            val token = token(household.id, memberId) ?: continue
            runCatching { withToken(household.serverUrl, token).send(HttpMethod.Delete, "/api/users/me/device-token/" + encodeQueryValue(endpoint)) }
        }
    }

    // ── The web garden, signed in ──────────────────────────

    /**
     * A link that opens [path] of the household's site in the browser signed
     * in as the active member: `{server}/login?token=<maur_>&to=<path>`. A
     * bare link would stop at the members-only gate. The `maur_` token comes
     * from `POST /api/users/me/mcp-token` and is kept per member. Without a
     * token (offline, refused) it is the bare page, which asks to sign in —
     * never an exception: the callers launch it from a click (GardenLinks.webURL).
     */
    suspend fun signedInUrl(path: String? = null, theme: String? = null): String? {
        val household = _state.value.current ?: return null
        val memberId = household.activeMemberId ?: return null
        if (memberId.isEmpty()) return null
        if (path != null && !path.startsWith("/")) return null
        val token = try {
            mcpToken()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } ?: return household.serverUrl.trimEnd('/') + (path ?: "")
        val query = buildList {
            add("token=" + encodeQueryValue(token))
            if (theme != null) add("theme=" + encodeQueryValue(theme))
            if (path != null) add("to=" + encodeQueryValue(path))
        }.joinToString("&")
        return household.serverUrl.trimEnd('/') + "/login?" + query
    }

    // ── Preferences ────────────────────────────────────────

    /** Change the active member's preferences; palette and language go to the server too. */
    suspend fun setPrefs(prefs: Prefs) {
        val memberId = _state.value.activeMember?.id ?: return
        storePrefs(memberId, prefs)
        _prefs.value = prefs
        val api = api() ?: return
        runCatching {
            api.send(HttpMethod.Patch, "/api/users/me/preferences", buildJsonObject {
                put("palette", prefs.palette)
                // Always sent, as null for "the device's": an omitted key
                // left the previous choice in place on the server.
                put("locale", prefs.locale)
            })
        }
    }

    /**
     * Signing in on this device takes what the member chose elsewhere, and
     * tells the server the device's language ([deviceLanguage], what the
     * interface speaks while the member follows the device) and what the
     * app runs on.
     */
    suspend fun loadServerPrefs(deviceLanguage: String? = null) {
        val memberId = _state.value.activeMember?.id ?: return
        val api = api() ?: return
        val server = runCatching { api.get<ServerPrefs>("/api/users/me/preferences") }.getOrNull() ?: return
        if (_state.value.activeMember?.id != memberId) return
        val next = _prefs.value.copy(palette = server.palette ?: "auto", locale = server.locale)
        storePrefs(memberId, next)
        _prefs.value = next
        val language = deviceLanguage?.let(::baseLanguage)
        if (language != null && server.deviceLocale != language) {
            runCatching { api.send(HttpMethod.Patch, "/api/users/me/preferences", buildJsonObject { put("device_locale", language) }) }
        }
        reportDevice(api, memberId)
    }

    /**
     * Carnet (`CarnetSession.refreshLanguage`): only the language the active
     * member chose in Maurice — the look is Carnet's own. Kept as it was when
     * the server cannot say.
     */
    suspend fun loadServerLocale() {
        val memberId = _state.value.activeMember?.id ?: return
        val api = api() ?: return
        val server = runCatching { api.get<ServerPrefs>("/api/users/me/preferences") }.getOrNull() ?: return
        if (_state.value.activeMember?.id != memberId) return
        val next = _prefs.value.copy(locale = server.locale)
        storePrefs(memberId, next)
        _prefs.value = next
    }

    /**
     * Tells the household's server what this app runs on, when one of those
     * things changed or a week after it last said so. The id is one this
     * install made up.
     */
    private suspend fun reportDevice(api: ApiClient, memberId: String) {
        val d = device ?: return
        val installId = store.get("$prefix.installId") ?: newId().lowercase().also { store.put("$prefix.installId", it) }
        val signature = listOf(d.platform, d.model.orEmpty(), d.osVersion.orEmpty(), d.appVersion.orEmpty()).joinToString("|")
        val sigKey = "$prefix.device.$memberId.sent"
        val atKey = "$prefix.device.$memberId.sentAt"
        val now = kotlin.time.Clock.System.now().epochSeconds
        val last = store.get(atKey)?.toLongOrNull() ?: 0
        if (store.get(sigKey) == signature && now - last < 7 * 86_400) return
        runCatching {
            api.send(HttpMethod.Post, "/api/users/me/device", buildJsonObject {
                put("install_id", installId)
                put("platform", d.platform)
                put("model", d.model?.take(40))
                put("os_version", d.osVersion?.take(20))
                put("app", d.app)
                put("app_version", d.appVersion?.take(20))
            })
        }.onSuccess {
            store.put(sigKey, signature)
            store.put(atKey, now.toString())
        }
    }

    private fun localPrefs(): Prefs {
        val memberId = _state.value.activeMember?.id ?: return Prefs()
        return Prefs(
            palette = store.get("$prefix.prefs.$memberId.palette") ?: "auto",
            locale = store.get("$prefix.prefs.$memberId.locale"),
            webTheme = store.get("$prefix.prefs.$memberId.webTheme") ?: "manuscript",
        )
    }

    private fun storePrefs(memberId: String, p: Prefs) {
        store.put("$prefix.prefs.$memberId.palette", p.palette)
        store.put("$prefix.prefs.$memberId.locale", p.locale)
        store.put("$prefix.prefs.$memberId.webTheme", p.webTheme)
    }

    // ── The member's own token and face ────────────────────

    /**
     * The member's `maur_` token (for an MCP client, or the web signed in),
     * kept per member; [rotate] mints a new one and revokes the old.
     */
    suspend fun mcpToken(rotate: Boolean = false): String? {
        val household = _state.value.current ?: return null
        val memberId = household.activeMemberId ?: return null
        val key = "$prefix.mcp-token.${household.id}.$memberId"
        if (!rotate) store.getSecret(key)?.let { return it }
        val api = api() ?: return null
        val minted: McpToken = api.post("/api/users/me/mcp-token", buildJsonObject { put("rotate", rotate) })
        store.putSecret(key, minted.rawToken)
        return minted.rawToken
    }

    /** A new photo: `PUT /api/users/:id/avatar` with a square JPEG; the member takes its new address. */
    suspend fun uploadAvatar(dataUri: String) {
        val household = _state.value.current ?: return
        val memberId = household.activeMemberId ?: return
        val api = api() ?: return
        val updated: Member = api.decode(api.send(HttpMethod.Put, "/api/users/$memberId/avatar", buildJsonObject { put("image", dataUri) }))
        updateHousehold(household.id) { h -> h.copy(members = h.members.map { if (it.id == memberId) it.copy(avatarUrl = updated.avatarUrl) else it }) }
    }

    // ── Local flags ────────────────────────────────────────

    /** Non-owners see once, per server, who runs the server they joined. */
    fun needsConnectDisclosure(): Boolean {
        val state = _state.value
        val member = state.activeMember ?: return false
        if (member.role == Role.Admin) return false
        return store.get("$prefix.connect_disclosure.${host(state.current!!.serverUrl)}") == null
    }

    fun acceptConnectDisclosure() {
        val household = _state.value.current ?: return
        store.put("$prefix.connect_disclosure.${host(household.serverUrl)}", "1")
    }

    // ── Shared with the other app (SharedPairings) ─────────

    /** What the two apps last agreed on, as this one knows it. */
    private var sharedSeen: SharedPairings? = null
    /**
     * Set while the other app's pairings are applied here, so that applying
     * them does not write them back — and until they have been read once, so
     * that this app's own copy does not overwrite a newer one.
     */
    private var applyingShared = true

    /** These households, members and sessions, as the other app is to see them. */
    private fun sharedSnapshot(): SharedPairings {
        val state = _state.value
        return SharedPairings(
            households = state.households.map { h ->
                SharedPairings.Household(
                    server = h.serverUrl, key = h.key, name = h.name, color = h.color, icon = h.icon,
                    members = h.members, active = h.activeMemberId,
                    tokens = h.members.mapNotNull { m -> token(h.id, m.id)?.let { m.id to it } }.toMap(),
                )
            },
            current = state.current?.serverUrl,
            deviceId = deviceId,
        )
    }

    /** The first launch after the update: what each app held becomes what both hold. Afterwards, what is shared counts. */
    private fun joinShared() {
        applyingShared = false
        val channel = shared ?: return
        if (store.get("$prefix.shared.joined") != "1") {
            val theirs = channel.read()
            channel.write(if (theirs != null) sharedSnapshot().absorb(theirs) else sharedSnapshot())
            store.put("$prefix.shared.joined", "1")
        }
        applyShared()
    }

    /** Something changed here: the other app hears of it. */
    private fun publishShared() {
        val channel = shared ?: return
        if (applyingShared || store.get("$prefix.shared.joined") != "1") return
        val snapshot = sharedSnapshot()
        if (snapshot == sharedSeen) return
        channel.write(snapshot)
        sharedSeen = snapshot
    }

    /**
     * The other app may have changed something: taken as it is, in place.
     * Called when it says so, and when this app comes back to the foreground.
     */
    fun applyShared() {
        val channel = shared ?: return
        if (store.get("$prefix.shared.joined") != "1") return
        val pairings = channel.read() ?: return
        if (pairings == sharedSeen) return
        sharedSeen = pairings
        // One id for the phone: both apps settle on the smaller of theirs.
        pairings.deviceId?.let { theirs -> if (theirs < deviceId) store.put(DEVICE_ID, theirs) }
        if (pairings.deviceId != deviceId) channel.write(sharedSnapshot().also { sharedSeen = it })
        applyingShared = true
        try {
            val before = _state.value.households
            val next = pairings.households.map { s ->
                val local = before.firstOrNull { s.same(it.serverUrl, it.key) }
                val id = local?.id ?: newId()
                // Joined in the other app: the warning before joining was read there (as on iOS,
                // which shows it while pairing, never for a household the other app brought).
                if (local == null) store.put("$prefix.connect_disclosure.${host(s.server)}", "1")
                val known = local?.members.orEmpty().associateBy { it.id }
                val members = s.members.map { m -> m.copy(username = m.username ?: known[m.id]?.username) }
                for (m in members) {
                    val t = s.tokens[m.id]
                    if (t != null) { if (token(id, m.id) != t) putToken(id, m.id, t) }
                    else if (token(id, m.id) != null) forgetTokens(id, m.id)
                }
                for (m in local?.members.orEmpty()) if (members.none { it.id == m.id }) forgetTokens(id, m.id)
                Household(
                    id = id, serverUrl = s.server, name = s.name, color = s.color, icon = s.icon,
                    members = members, activeMemberId = s.active, key = s.key ?: local?.key, unread = local?.unread,
                    // Kept on this device only: the other app does not carry it.
                    invite = local?.invite,
                )
            }
            for (gone in before) if (next.none { it.id == gone.id }) gone.members.forEach { forgetTokens(gone.id, it.id) }
            val current = next.firstOrNull { it.serverUrl == pairings.current }?.id ?: next.firstOrNull()?.id
            if (next != before || current != _state.value.currentId) mutate { it.copy(households = next, currentId = current) }
        } finally {
            applyingShared = false
        }
    }

    private fun forgetTokens(householdId: String, memberId: String) {
        putToken(householdId, memberId, null)
        store.putSecret("$prefix.mcp-token.$householdId.$memberId", null)
    }

    // ── Storage ────────────────────────────────────────────

    fun hasToken(householdId: String, memberId: String) = token(householdId, memberId) != null

    private fun token(householdId: String, memberId: String) = store.getSecret("$prefix.token.$householdId.$memberId")
    private fun putToken(householdId: String, memberId: String, token: String?) {
        store.putSecret("$prefix.token.$householdId.$memberId", token)
        publishShared()
    }

    private fun household(id: String) = _state.value.households.firstOrNull { it.id == id }
    private fun requireCurrent() = _state.value.current ?: throw ApiError(0, "no_household")

    private fun setActive(householdId: String, memberId: String?) =
        updateHousehold(householdId) { it.copy(activeMemberId = memberId) }

    private fun updateHousehold(id: String, change: (Household) -> Household) = mutate { state ->
        state.copy(households = state.households.map { if (it.id == id) change(it) else it })
    }

    private fun mutate(change: (SessionState) -> SessionState) {
        val before = _state.value.activeMember?.id to _state.value.currentId
        _state.update(change)
        val state = _state.value
        // Another member, another household: their own preferences.
        if (before != (state.activeMember?.id to state.currentId)) _prefs.value = localPrefs()
        store.put("$prefix.households", MauriceJson.encodeToString(ListSerializer(Household.serializer()), state.households))
        store.put("$prefix.currentHousehold", state.currentId)
        publishShared()
    }

    private fun load(): SessionState {
        val households = store.get("$prefix.households")?.let {
            runCatching { MauriceJson.decodeFromString(ListSerializer(Household.serializer()), it) }.getOrNull()
        }.orEmpty()
        val current = store.get("$prefix.currentHousehold")?.takeIf { id -> households.any { it.id == id } }
        return SessionState(households, current ?: households.firstOrNull()?.id)
    }

    private fun host(url: String) = runCatching { Url(url).host }.getOrDefault(url)

    private fun newId(): String {
        val bytes = Random.nextBytes(16)
        bytes[6] = (bytes[6].toInt() and 0x0f or 0x40).toByte()
        bytes[8] = (bytes[8].toInt() and 0x3f or 0x80).toByte()
        val hex = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}".uppercase()
    }

    // Last, once every property above is set: what the other app holds is read.
    init { joinShared() }
}
