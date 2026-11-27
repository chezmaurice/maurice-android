package eu.chezmaurice.maurice.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.model.Household
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.session.EnrollOutcome
import eu.chezmaurice.core.session.PendingEnrollment
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.session.typedInviteCode
import eu.chezmaurice.maurice.absoluteUrl
import eu.chezmaurice.maurice.describe
import eu.chezmaurice.ui.Avatar
import eu.chezmaurice.ui.BoaterHat
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.ui.hexColor
import eu.chezmaurice.ui.wordmark
import kotlinx.coroutines.launch

/** The eight colours on offer (`MemberColors`): a newcomer picks one, the admin's member sheet offers the same. */
internal val PROFILE_COLOURS = listOf("#2c5aa0", "#7a4f6e", "#b97a1e", "#3d6b4f", "#a6452e", "#e3a7c2", "#44504f", "#9c6b4a")

private sealed interface Panel {
    data object Code : Panel
    data class Profile(val code: String, val household: String?) : Panel
    data class PinSetup(val pending: PendingEnrollment) : Panel
    data class PinEntry(val member: Member) : Panel
    data object Admin : Panel
}

/**
 * "Who's there?" — the household's members, and the ways in: an invitation
 * code, a PIN, the admin's password. A fresh device has no member list (the
 * list needs a token), so it starts from a code.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemberPickerScreen(session: Session, household: Household) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var panel by remember { mutableStateOf<Panel?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** This device was signed out of its session (picker.session_ended): not an error. */
    var ended by remember { mutableStateOf(false) }

    /** Not an error: the household's address is not known on this network yet, and is waited for. */
    var notice by remember { mutableStateOf<String?>(null) }

    /**
     * Enrol with [code]. An address the network does not know yet is waited
     * for, not refused: a household just made can stay "not found" for
     * minutes on a network that asked for its name too early — every five
     * seconds, ten minutes at most (`enrollWithCode`, 20 November 2026).
     */
    fun enroll(code: String, name: String? = null, colour: String? = null) {
        busy = true; error = null
        scope.launch {
            val host = hostOf(household.serverUrl)
            try {
                repeat(120) {
                    try {
                        when (val outcome = session.enroll(code, name, colour)) {
                            is EnrollOutcome.NeedsProfile -> panel = Panel.Profile(code, outcome.household)
                            is EnrollOutcome.NeedsPin -> panel = Panel.PinSetup(outcome.pending)
                            is EnrollOutcome.SignedIn -> panel = null
                        }
                        return@launch
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (!eu.chezmaurice.core.signup.isNoHost(e)) {
                            error = if (e is ApiError && e.status == 401) strings("enroll.failed") else strings.describe(e)
                            return@launch
                        }
                        notice = strings("pairing.error.not_yet", host)
                        kotlinx.coroutines.delay(5_000)
                        if (session.state.value.currentId != household.id) return@launch
                    }
                }
                error = strings("pairing.error.no_host", host)
            } finally { busy = false; notice = null }
        }
    }

    /** The invitation the household kept, used as when it first arrived. */
    fun resumeInvite() { household.invite?.let { error = null; enroll(it) } }

    /**
     * A code handed over by a scan, a link or the sign-up's wait. Not used a
     * second time when it let someone in here less than a minute ago (the
     * same invitation passed on twice): the server would count a new device
     * (22 November 2026).
     */
    fun fresh(code: String) { if (!session.justServed(household.serverUrl, code)) enroll(code) }

    LaunchedEffect(household.id) {
        if (session.state.value.endedHouseholdId == household.id) {
            // Signed out from another device, a PIN changed, ninety days unused:
            // said in grey, not as a failure (26 November 2026).
            ended = true
            session.acknowledgeEndedSession()
        }
        session.pendingEnrollment(household.id)?.let { panel = Panel.PinSetup(it) }
        PendingInvitation.code?.let { code -> PendingInvitation.code = null; fresh(code) }
        runCatching { session.refreshMembers() }
    }
    // An invitation that arrives while this is shown (a link tapped from another app).
    LaunchedEffect(Unit) {
        PendingInvitation.flow.collect { code -> if (code != null) { PendingInvitation.code = null; fresh(code) } }
    }

    var options by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    EntryGround {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(EntryMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
      EntryCard {
        AppEmblem(72.dp)
        Spacer(Modifier.height(16.dp))
        Text(household.name, style = wordmark(28.sp), color = theme.ink, textAlign = TextAlign.Center)
        Text("${LocalAppBrand.current.name} · Chez Maurice", fontSize = 13.sp, color = theme.inkMute)
        Spacer(Modifier.height(28.dp))
        Text(L("picker.title"), fontSize = 20.sp, color = theme.ink)
        Spacer(Modifier.height(24.dp))

        // A fresh device knows no one yet: what to do, rather than an empty grid.
        // An invitation received here and not used yet stays on offer (Household.invite).
        val kept = household.invite
        if (household.members.isEmpty()) {
            Text(L(if (kept != null) "picker.invite_waiting" else "picker.empty"), fontSize = 14.sp, color = theme.inkSoft, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
        }
        if (household.members.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
            ) {
                for (member in household.members.sortedBy { it.displayName.lowercase() }) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.widthIn(min = 84.dp, max = 132.dp).clip(MaterialTheme.shapes.medium).clickable {
                            error = null
                            when {
                                member.hasPin -> panel = Panel.PinEntry(member)
                                session.switchTo(member) -> {}
                                else -> {
                                    // No PIN and no token here: an invitation code is the only way in.
                                    error = strings("picker.needs_invite", member.displayName)
                                    panel = Panel.Code
                                }
                            }
                        }.padding(6.dp),
                    ) {
                        Avatar(member.displayName, member.avatarColor, absoluteUrl(household.serverUrl, member.avatarUrl), size = 64.dp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            member.displayName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = theme.ink,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        )
                        if (member.hasPin) {
                            Icon(Icons.Default.Lock, contentDescription = L("sheet.pin_required"), tint = theme.inkMute, modifier = Modifier.size(11.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }

        Button(
            onClick = { error = null; if (kept != null) resumeInvite() else panel = Panel.Code },
            enabled = !busy || panel != null,
            colors = ButtonDefaults.buttonColors(containerColor = theme.ink, contentColor = theme.bg),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy && panel == null) CircularProgressIndicator(Modifier.size(18.dp), color = theme.bg, strokeWidth = 2.dp)
            else Text(L(if (kept != null) "picker.invite_kept" else "enroll.cta"), fontSize = 16.sp)
        }
        notice?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = theme.inkSoft, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(4.dp))
        // The admin's password and forgetting the household: rarer, out of the way.
        Box {
            TextButton(onClick = { options = true }) { Text(L("welcome.more_options"), color = theme.inkSoft, fontSize = 14.sp) }
            androidx.compose.material3.DropdownMenu(expanded = options, onDismissRequest = { options = false }, containerColor = theme.surface) {
                androidx.compose.material3.DropdownMenuItem(text = { Text(L("picker.admin_signin")) }, onClick = { options = false; error = null; panel = Panel.Admin })
                androidx.compose.material3.DropdownMenuItem(text = { Text(L("picker.disconnect")) }, onClick = { options = false; forgetting = true })
            }
        }
        if (error == null && ended) {
            Spacer(Modifier.height(16.dp))
            Text(L("picker.session_ended"), color = theme.inkSoft, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
      }
    }
    }
    if (forgetting) ForgetHouseholdDialog(session, household) { forgetting = false }

    when (val p = panel) {
        null -> {}
        Panel.Code -> CodeDialog(busy, error, notice, onDismiss = { panel = null }) { enroll(it) }
        is Panel.Profile -> ProfileDialog(p.household, busy, error, onDismiss = { panel = null }) { name, colour -> enroll(p.code, name, colour) }
        // Required after enrolling, as on iOS: no way out but a PIN.
        is Panel.PinSetup -> PinSetupDialog(busy, error) { pin ->
            busy = true; error = null
            scope.launch {
                try { session.setPin(p.pending, pin); panel = null } catch (e: Throwable) { error = strings.describe(e) } finally { busy = false }
            }
        }
        is Panel.PinEntry -> PinEntryDialog(p.member, busy, error, onDismiss = { panel = null }) { pin ->
            busy = true; error = null
            scope.launch {
                try { session.loginWithPin(p.member, pin); panel = null }
                catch (e: Throwable) { error = if (e is ApiError && e.status == 401) strings("picker.pin.error") else strings.describe(e) }
                finally { busy = false }
            }
        }
        Panel.Admin -> AdminDialog(busy, error, onDismiss = { panel = null }) { username, password ->
            busy = true; error = null
            scope.launch {
                try { session.adminLogin(username, password); panel = null } catch (e: Throwable) { error = strings.describe(e) } finally { busy = false }
            }
        }
    }
}

/**
 * A small panel over the picker. Not an AlertDialog: that one stays put when
 * the keyboard opens and its buttons end up under it. This one rides above
 * the keyboard, and the keyboard's own action key confirms (see [Confirm]).
 */
@Composable
private fun Sheet(
    title: String,
    subtitle: String?,
    confirm: String,
    canConfirm: Boolean,
    busy: Boolean,
    error: String?,
    /** Null: the step is required — no Cancel, and neither back nor a tap outside closes it (iOS's PinSetupSheet). */
    onDismiss: (() -> Unit)?,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    val theme = LocalMauriceTheme.current
    val submit = { if (canConfirm && !busy) onConfirm() }
    Dialog(
        onDismissRequest = { if (!busy) onDismiss?.invoke() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = onDismiss != null, dismissOnClickOutside = onDismiss != null),
    ) {
        Box(Modifier.fillMaxSize().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp)).background(theme.surface)
                    .verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, color = theme.ink, fontSize = 22.sp)
                subtitle?.let { Text(it, color = theme.inkSoft, fontSize = 14.sp) }
                CompositionLocalProvider(LocalConfirm provides submit) { content() }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (onDismiss != null) TextButton(onClick = onDismiss, enabled = !busy) { Text(L("picker.pin.cancel"), color = theme.inkSoft) }
                    TextButton(onClick = submit, enabled = canConfirm && !busy) { Text(confirm, color = theme.ink) }
                }
            }
        }
    }
}

/** What the keyboard's action key does inside a [Sheet]: the sheet's confirm. */
private val LocalConfirm = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
private fun confirmOnDone(): KeyboardActions {
    val confirm = LocalConfirm.current
    return KeyboardActions(onDone = { confirm() })
}

@Composable
private fun CodeDialog(busy: Boolean, error: String?, notice: String?, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf(TextFieldValue("")) }
    val clean = code.text.uppercase().filter { it.isLetterOrDigit() }
    Sheet(L("enroll.title"), L("enroll.subtitle"), L("enroll.submit"), clean.length == 8, busy, error, onDismiss, { onSubmit(clean) }) {
        OutlinedTextField(
            value = code,
            onValueChange = {
                val formatted = typedInviteCode(it.text)
                code = TextFieldValue(formatted, selection = TextRange(formatted.length))
            },
            placeholder = { Text(L("enroll.placeholder")) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = confirmOnDone(),
        )
        // Waited for, not refused: in the ink of a caption, not an error's red.
        notice?.let { Text(it, fontSize = 13.sp, color = LocalMauriceTheme.current.inkSoft) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileDialog(household: String?, busy: Boolean, error: String?, onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    val theme = LocalMauriceTheme.current
    var name by remember { mutableStateOf("") }
    var colour by remember { mutableStateOf(PROFILE_COLOURS.first()) }
    Sheet(L("enroll.profile.title"), listOfNotNull(household, L("enroll.profile.subtitle")).joinToString("\n"), L("enroll.submit"),
        name.isNotBlank(), busy, error, onDismiss, { onSubmit(name.trim().take(40), colour) }) {
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(40) }, singleLine = true,
            placeholder = { Text(L("enroll.profile.name")) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = confirmOnDone(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (c in PROFILE_COLOURS) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(hexColor(c))
                        .then(if (c == colour) Modifier.border(3.dp, theme.ink, CircleShape) else Modifier)
                        .clickable { colour = c },
                )
            }
        }
    }
}

@Composable
private fun PinField(value: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(6)) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = confirmOnDone(),
    )
}

@Composable
private fun PinSetupDialog(busy: Boolean, error: String?, onSubmit: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val local = when {
        pin.isNotEmpty() && pin.length < 4 -> L("pinsetup.invalid")
        // The PINs everybody tries first, said before the server refuses them.
        pin.length >= 4 && eu.chezmaurice.core.session.PinRules.tooEasy(pin) -> L("pinsetup.weak")
        again.length >= pin.length && again.isNotEmpty() && again != pin -> L("pinsetup.mismatch")
        else -> null
    }
    Sheet(L("pinsetup.title"), L("pinsetup.subtitle"), L("pinsetup.submit"),
        pin.length in 4..6 && pin == again && !eu.chezmaurice.core.session.PinRules.tooEasy(pin), busy, local ?: error, null, { onSubmit(pin) }) {
        PinField(pin, L("pinsetup.placeholder")) { pin = it }
        PinField(again, L("pinsetup.confirm")) { again = it }
    }
}

@Composable
private fun PinEntryDialog(member: Member, busy: Boolean, error: String?, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    // A PIN refused is cleared for the next try, as on iOS.
    androidx.compose.runtime.LaunchedEffect(error) { if (error != null) pin = "" }
    Sheet(member.displayName, L("picker.pin.title"), L("picker.pin.submit"), pin.length in 4..6, busy, error, onDismiss, { onSubmit(pin) }) {
        PinField(pin, L("picker.pin.placeholder")) { pin = it }
    }
}

@Composable
private fun AdminDialog(busy: Boolean, error: String?, onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Sheet(L("picker.admin.title"), L("picker.admin.subtitle"), L("picker.admin.submit"),
        username.isNotBlank() && password.isNotEmpty(), busy, error, onDismiss, { onSubmit(username, password) }) {
        OutlinedTextField(
            value = username, onValueChange = { username = it }, singleLine = true,
            placeholder = { Text(L("picker.admin.username")) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it }, singleLine = true,
            placeholder = { Text(L("picker.admin.password")) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = confirmOnDone(),
        )
    }
}
