package eu.chezmaurice.maurice.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.carnet.CarnetEntry
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.session.Invitation
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.maurice.describe
import eu.chezmaurice.maurice.platform.QrScanner
import eu.chezmaurice.maurice.platform.canScanQr
import eu.chezmaurice.maurice.resources.Res
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.ui.wordmark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * Which app the way in belongs to: its name in large, "Chez Maurice" under
 * it, what it promises, and its icon's emblem. The same screens serve both
 * apps; only this changes — and how each app reads what is typed or scanned
 * ([invitation], [code], [address]: Carnet's are stricter, as on iOS).
 */
data class AppBrand(
    val name: String, val promiseKey: String, val emblem: String, val tile: String, val darkTile: Boolean,
    val invitation: (String) -> Invitation? = Invitation::parse,
    /** The code typed alone, as letters and digits; null when it is not one. */
    val code: (String) -> String? = { t -> t.uppercase().filter { it.isLetterOrDigit() }.takeIf { looksLikeCode(t) } },
    /** The household's address as paired; null when it is not one. */
    val address: (String) -> String? = { it },
) {
    companion object {
        // The two iOS apps read what is typed or scanned differently: each its own rules.
        val Maurice = AppBrand(
            "Maurice", "welcome.promise.maurice", "files/emblems/maurice.png", "files/tiles/maurice.png", darkTile = false,
            invitation = eu.chezmaurice.core.session.MauriceInvitation::parse,
            address = eu.chezmaurice.core.session.MauriceInvitation::address,
        )
        val Carnet = AppBrand(
            "Carnet", "welcome.promise.carnet", "files/emblems/carnet.png", "files/tiles/carnet.png", darkTile = true,
            invitation = CarnetEntry::invitation,
            code = { t -> CarnetEntry.code(t)?.filter { it != '-' } },
            address = CarnetEntry::address,
        )
    }
}

val LocalAppBrand = staticCompositionLocalOf { AppBrand.Maurice }

/** The app's icon, as on the home screen. */
@Composable
fun AppEmblem(size: androidx.compose.ui.unit.Dp) {
    val brand = LocalAppBrand.current
    val image by produceState<ImageBitmap?>(null, brand.emblem) {
        value = runCatching { Res.readBytes(brand.emblem).decodeToImageBitmap() }.getOrNull()
    }
    // The emblem alone (bowler hat, pipe), in ink: the tile is already behind, as on iOS.
    Box(Modifier.size(size)) {
        image?.let {
            Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(LocalMauriceTheme.current.ink))
        }
    }
}

/**
 * The way in, the same in both apps (`PairingView.swift`): the app's name and
 * promise, then one main action — scan the invitation on a phone, paste its
 * link on a computer — and one field for a code, a link or an address.
 * The admin's password comes after, on "Who's here?" (More options).
 * [onCancel], when it is opened to add another household: a Cancel button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(session: Session, onPaired: () -> Unit = {}, onCancel: (() -> Unit)? = null) {
    val theme = LocalMauriceTheme.current
    val brand = LocalAppBrand.current
    val strings = LocalStrings.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var entering by remember { mutableStateOf<Entry?>(null) }
    // « Créer mon Maurice » (Maurice only): the sign-up, or the wait for one under way.
    val offersSignup = brand == AppBrand.Maurice
    var signingUp by remember { mutableStateOf(false) }
    var signupPending by remember { mutableStateOf(eu.chezmaurice.core.signup.SignupPoll.saved(session.store) != null) }
    /** What the sign-up opens with: an access code from a link, the address typed as the name wished for. */
    var signupCode by remember { mutableStateOf("") }
    var signupName by remember { mutableStateOf("") }
    fun signUp(code: String, name: String = "") {
        signupCode = code; signupName = name
        entering = null; scanning = false; error = null
        signingUp = true
    }
    // Back after quitting mid-sign-up: straight to the wait (not when adding another household).
    if (offersSignup && onCancel == null) androidx.compose.runtime.LaunchedEffect(Unit) {
        if (signupPending) { kotlinx.coroutines.delay(500); signingUp = true }
    }
    // An access code that came by a link (maurice://signup, the tower's QR code): the sign-up, with it filled in.
    if (offersSignup) androidx.compose.runtime.LaunchedEffect(Unit) {
        AccessCodeLinks.pending.collect { code -> if (code != null) { AccessCodeLinks.pending.value = null; signUp(code) } }
    }
    val tower = remember { eu.chezmaurice.core.signup.SignupTower(eu.chezmaurice.core.net.ApiClient.defaultHttpClient()) }

    /** An invitation, or an address with what to do once the household is paired. */
    fun join(invitation: Invitation?, address: String? = null, code: String? = null) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            // The code is handed over before pairing: once the household is
            // paired the member picker replaces this screen, and this
            // coroutine, in its scope, is cancelled at its next suspension —
            // a code set afterwards never reached the picker, which then
            // asked for it again after a scanned invitation. No picker is
            // shown while this screen is, so no other household takes it.
            val handed = invitation?.code ?: code
            try {
                // A hosted address is asked of the tower before it is asked of
                // the network: a name looked up before it exists stays "not
                // found" on this network for half an hour, and would still be
                // when someone then makes a Maurice of that very name (20 November 2026).
                var target = address
                val name = if (offersSignup && invitation == null && address != null) eu.chezmaurice.core.signup.hostedName(address) else null
                if (name != null) {
                    val hosted = "$name.${tower.zone}"
                    val answer = try { tower.checkName(name) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { null }
                    // Free: nobody lives there.
                    if (answer?.ok == true) { error = strings("pairing.error.no_host", hosted); return@launch }
                    if (answer?.reason == "taken") target = "https://$hosted"
                }
                handed?.let { PendingInvitation.code = it }
                if (invitation != null) {
                    // Carried through the cancellation, so the households
                    // are refreshed: the new one takes its name and colour.
                    withContext(NonCancellable) { session.receive(invitation) }
                } else if (target != null) {
                    session.connect(target, brand.address)
                }
                entering = null
                onPaired()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (handed != null && PendingInvitation.code == handed) PendingInvitation.code = null
                error = strings.pairingError(e, address)
            } finally { busy = false }
        }
    }

    /** What was scanned or pasted: a household's invitation, or (Maurice) the tower's access code. */
    fun taken(text: String): Boolean {
        brand.invitation(text)?.let { join(it); return true }
        if (offersSignup) eu.chezmaurice.core.signup.accessCode(text)?.let { signUp(it); return true }
        return false
    }

    EntryGround {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(EntryMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
      EntryCard {
        Spacer(Modifier.height(8.dp))
        AppEmblem(112.dp)
        // The emblem's picture has room around it: little more is needed.
        Spacer(Modifier.height(4.dp))
        Text(brand.name, style = wordmark(40.sp), color = theme.ink)
        Text("Chez Maurice", fontSize = 15.sp, color = theme.inkMute)
        Spacer(Modifier.height(20.dp))
        Text(L(brand.promiseKey), fontSize = 17.sp, color = theme.inkSoft, textAlign = TextAlign.Center, lineHeight = 24.sp,
            modifier = Modifier.widthIn(max = 360.dp))
        Spacer(Modifier.height(12.dp))
        Text(L("pairing.instructions"), fontSize = 14.sp, color = theme.inkMute, textAlign = TextAlign.Center, lineHeight = 20.sp,
            modifier = Modifier.widthIn(max = 360.dp))
        Spacer(Modifier.height(32.dp))

        val wide = Modifier.fillMaxWidth().height(52.dp)
        Button(
            onClick = {
                error = null
                if (canScanQr) scanning = true
                else {
                    val text = clipboard.getText()?.text.orEmpty()
                    if (!taken(text)) error = strings("invite.scan.not_invitation")
                }
            },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = theme.ink, contentColor = theme.bg),
            modifier = wide,
        ) {
            if (busy && entering == null) CircularProgressIndicator(Modifier.size(18.dp), color = theme.bg, strokeWidth = 2.dp)
            else Text(L(if (canScanQr) "invite.scan" else "welcome.paste_link"), fontSize = 16.sp)
        }
        Spacer(Modifier.height(10.dp))
        FilledTonalButton(
            onClick = { error = null; entering = Entry.Any },
            enabled = !busy,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = theme.surfaceAlt, contentColor = theme.ink),
            modifier = wide,
        ) { Text(L("welcome.enter_code"), fontSize = 16.sp) }
        // No Maurice yet: make one. A sign-up under way is picked up again here, even after quitting the app.
        if (offersSignup) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { error = null; signingUp = true }, enabled = !busy) {
                Text(L(if (signupPending) "signup.resume" else "signup.open"), fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = theme.ink)
            }
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
      }
    }
    // Opened to add another household: a way out that is not a gesture (the desktop has none).
    if (onCancel != null) OutlinedButton(
        onClick = onCancel,
        modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(16.dp),
    ) { Text(L("common.cancel"), color = theme.ink) }
    }

    entering?.let { entry ->
        ModalBottomSheet(
            onDismissRequest = { if (!busy) entering = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = theme.surface,
        ) {
            // The code is the tower's, for a Maurice still to be made: on to the
            // sign-up with it, and with the address typed as the name wished for.
            val withCode: ((String) -> Unit)? = (entry as? Entry.CodeNeedsAddress)?.takeIf { offersSignup }?.let { e ->
                { typed -> signUp(e.code, eu.chezmaurice.core.signup.hostedName(typed, tower.zone).orEmpty()) }
            }
            EntrySheet(entry, busy, error, withCode) { text, address ->
                val invitation = brand.invitation(text)
                val code = brand.code(text)
                val access = if (offersSignup && entry == Entry.Any) eu.chezmaurice.core.signup.accessCode(text) else null
                when {
                    invitation != null -> join(invitation)
                    access != null -> signUp(access)
                    code != null && address.isNullOrBlank() -> entering = Entry.CodeNeedsAddress(code)
                    code != null -> join(null, address = address, code = code)
                    entry is Entry.CodeNeedsAddress -> join(null, address = text, code = entry.code)
                    else -> join(null, address = text)
                }
            }
        }
    }

    if (signingUp) {
        fun closed() { signingUp = false; signupPending = eu.chezmaurice.core.signup.SignupPoll.saved(session.store) != null }
        SignupDialog(session, code = signupCode, name = signupName, onJoined = { closed(); onPaired() }, onDismiss = ::closed)
    }

    if (scanning) {
        QrScanner(
            onResult = { text ->
                scanning = false
                if (!taken(text)) error = strings("invite.scan.not_invitation")
            },
            onCancel = { scanning = false },
        )
    }
}

/** What the sheet asks for. */
private sealed interface Entry {
    /** A code, a link or an address: whichever the member has. */
    data object Any : Entry
    /** A code alone: the household's address too. */
    data class CodeNeedsAddress(val code: String) : Entry
}

/** XXXX-XXXX or XXXXXXXX: no dot, no slash, eight letters and digits. */
internal fun looksLikeCode(text: String): Boolean {
    val t = text.trim()
    return '.' !in t && '/' !in t && ':' !in t && t.uppercase().filter { it.isLetterOrDigit() }.length == 8
}

@Composable
private fun EntrySheet(
    entry: Entry, busy: Boolean, error: String?,
    /** Maurice, a code alone: "No Maurice yet? Create one with this code", with what is typed. */
    withCode: ((typed: String) -> Unit)?,
    onSubmit: (text: String, address: String?) -> Unit,
) {
    val theme = LocalMauriceTheme.current
    var text by remember(entry) { mutableStateOf("") }
    val title = when (entry) {
        Entry.Any -> L("welcome.enter_code")
        is Entry.CodeNeedsAddress -> L("welcome.address_needed")
    }
    val urlPlaceholder = L("pairing.url_placeholder")
    val placeholder = when {
        entry == Entry.Any -> urlPlaceholder
        // The address alone: Carnet iOS takes it from its example in the member's language
        // (WelcomeView), the Maurice app writes it as is (PairingView).
        LocalAppBrand.current == AppBrand.Carnet -> urlPlaceholder.split(" ").lastOrNull() ?: "chezmaurice.eu"
        else -> "monfoyer.chezmaurice.eu"
    }
    val submit = { if (text.isNotBlank() && !busy) onSubmit(text.trim(), null) }
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title, style = wordmark(22.sp), color = theme.ink)
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            placeholder = { Text(placeholder) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        // The inviter's address, not one to invent: an access code typed here
        // belongs to the sign-up (20 November 2026).
        if (withCode != null) {
            Column {
                Text(L("welcome.address_caption"), fontSize = 13.sp, color = theme.inkMute, lineHeight = 18.sp)
                TextButton(onClick = { withCode(text.trim()) }, enabled = !busy, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(L("signup.with_code"), fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = theme.ink)
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        Button(
            onClick = submit, enabled = text.isNotBlank() && !busy,
            colors = ButtonDefaults.buttonColors(containerColor = theme.ink, contentColor = theme.bg),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = theme.bg, strokeWidth = 2.dp)
                Text(L("pairing.connect"), fontSize = 16.sp)
            }
        }
    }
}

/**
 * The code of an invitation just received, for the member picker to use
 * at once — `SessionStore.pendingInvitation`. Process-local on purpose.
 */
object PendingInvitation {
    /** Watched by the member picker, so a link opened while it is already shown is used too. */
    val flow = MutableStateFlow<String?>(null)
    var code: String?
        get() = flow.value
        set(value) { flow.value = value }
}

/**
 * An invitation link the system handed to the app — a tap on
 * `https://<foyer>/join?code=…` (App Link), `maurice://join` or
 * `carnet://join` — waiting for the app to take it.
 */
object InvitationLinks {
    private val pending = MutableStateFlow<Invitation?>(null)

    /** True when [url] was an invitation, as the app reads one ([AppBrand.invitation]), and is now waiting. */
    fun open(url: String, brand: AppBrand = AppBrand.Maurice): Boolean = brand.invitation(url)?.also { pending.value = it } != null

    /**
     * Pairs the household of each invitation that arrives and hands its code
     * to the member picker, which signs the newcomer in — the same road as
     * pasting the link on the pairing screen. Placed once at the app's root.
     */
    @Composable
    fun Handle(session: Session) {
        androidx.compose.runtime.LaunchedEffect(session) {
            pending.collect { invitation ->
                if (invitation == null) return@collect
                pending.value = null
                runCatching { session.receive(invitation) }.onSuccess { PendingInvitation.code = invitation.code }
            }
        }
    }
}

/**
 * An access code a link handed to the app (`SessionStore.pendingAccessCode`):
 * `maurice://signup?code=…` from the tower's page, or the tower's own link —
 * for a Maurice still to be made. The welcome screen takes it and opens the
 * sign-up with it; with a household already here, the app's root does.
 */
object AccessCodeLinks {
    val pending = MutableStateFlow<String?>(null)

    /** True when [url] carried an access code, now waiting. */
    fun open(url: String): Boolean = eu.chezmaurice.core.signup.accessCode(url)?.also { pending.value = it } != null
}

/** The host of what was typed, for "There is no Maurice at …". */
internal fun hostOf(address: String?): String {
    val raw = address?.trim().orEmpty()
    val url = eu.chezmaurice.core.session.MauriceInvitation.address(raw) ?: return raw
    return runCatching { io.ktor.http.Url(url).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: raw
}

/**
 * Why a household could not be reached, as PairingView says it on iOS: an
 * address that is none, a server that is no Maurice, a name the network does
 * not know, else "Could not connect: <reason>" — with the nudge toward https
 * only then, and only for an http:// address.
 */
internal fun eu.chezmaurice.ui.Strings.pairingError(e: Throwable, address: String?): String {
    // No such name: an address mistyped, or made up by someone who took the
    // field for the place to name a new Maurice (20 November 2026).
    if (eu.chezmaurice.core.signup.isNoHost(e) && has("pairing.error.no_host")) return this("pairing.error.no_host", hostOf(address))
    val api = e as? eu.chezmaurice.core.net.ApiError
    if (api?.message == "invalid_address") return this("pairing.error.invalid_url")
    if (api?.message == "not_a_maurice_server") return this("pairing.error.status")
    val reason = when {
        api != null && has(api.message) -> this(api.message)
        api != null && api.status != 0 -> api.shown
        else -> e.message?.takeIf { it.isNotBlank() } ?: this("pairing.error.unreachable")
    }
    val hint = if (address?.trim()?.lowercase()?.startsWith("http://") == true) "\n" + this("pairing.error.https_hint") else ""
    return this("pairing.error.connect", reason) + hint
}
