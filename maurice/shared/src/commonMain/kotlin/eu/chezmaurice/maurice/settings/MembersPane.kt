package eu.chezmaurice.maurice.settings

import eu.chezmaurice.ui.accessibilityLabel
import eu.chezmaurice.core.net.shownMessage
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.chezmaurice.core.model.Household
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.model.Role
import eu.chezmaurice.core.model.parseServerDate
import eu.chezmaurice.core.session.Invitation
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.session.formatInviteCode
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.settings.DeviceCodePurpose
import eu.chezmaurice.core.settings.OpenInvite
import eu.chezmaurice.core.settings.SettingsApi
import eu.chezmaurice.maurice.chat.legible
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.maurice.platform.encodeJpeg
import eu.chezmaurice.maurice.platform.qrMatrix
import eu.chezmaurice.maurice.platform.rememberShareText
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.hexColor
import eu.chezmaurice.ui.wordmark
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.max
import kotlin.time.ExperimentalTime

/** What the QR dialog shows: an open invitation, or a member's device code. */
private data class Shown(val code: String, val title: String, val expiresAt: String, val memberId: String?)

/**
 * Who is in the household, and letting someone in (admin, `MembersPane`):
 * the name of this Maurice; an open invitation brings someone new; a member
 * opens their sheet — name, colour, a new device, deleting them.
 */
@Composable
fun MembersPane(session: Session, household: Household, me: Member) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    val api = remember { session.api()?.let(::SettingsApi) } ?: return
    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var invites by remember { mutableStateOf<List<OpenInvite>>(emptyList()) }
    var shown by remember { mutableStateOf<Shown?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Member?>(null) }
    var householdName by remember { mutableStateOf(household.name) }
    var savedName by remember { mutableStateOf(household.name) }
    val strings = eu.chezmaurice.maurice.domains.strings()

    suspend fun attempt(block: suspend () -> Unit) {
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { error = e.shownMessage() }
    }
    LaunchedEffect(Unit) { attempt { members = api.members(); invites = api.invites() } }

    // "Why this code?": asked before a fresh code for someone else's account,
    // then the reason when the admin goes in themselves.
    var askWhy by remember { mutableStateOf<Member?>(null) }
    var reasonFor by remember { mutableStateOf<Member?>(null) }
    var reasonRefused by remember { mutableStateOf<String?>(null) }

    fun show(code: OpenInvite, m: Member) { shown = Shown(code.code, strings("members.new_device.for", m.displayName), code.expiresAt, m.id) }
    suspend fun makeDeviceCode(m: Member, purpose: DeviceCodePurpose?, reason: String?) {
        try { show(api.newDeviceCode(m.id, purpose, reason), m) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        // The server wants the reason: the question again, with its words.
        catch (e: ApiError) { if (e.code == "reason_required") { reasonRefused = e.message; reasonFor = m } else error = e.shownMessage() }
        catch (e: Throwable) { error = e.shownMessage() }
    }
    /**
     * The member's current device code, or a fresh one — after the admin has
     * said why, when the account is someone else's (an admin's own: nobody
     * to tell, nothing to ask).
     */
    fun showDeviceCode(m: Member) = scope.launch {
        attempt {
            val current = api.currentDeviceCode(m.id)
            when {
                current != null -> show(current, m)
                m.role == Role.Admin -> makeDeviceCode(m, null, null)
                else -> askWhy = m
            }
        }
    }
    val trimmedName = householdName.trim()
    fun saveHouseholdName() {
        if (busy || trimmedName.isEmpty() || trimmedName == savedName) return
        busy = true
        scope.launch {
            attempt {
                val kept = api.renameHousehold(trimmedName)
                householdName = kept; savedName = kept
                session.renameHousehold(household.id, kept)
            }
            busy = false
        }
    }

    // The household's name, as everyone's switcher shows it.
    SetGroup(L("members.household_name")) {
        SetCard {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.TextField(
                    householdName, { householdName = it }, singleLine = true, modifier = Modifier.weight(1f),
                    placeholder = { Text(L("members.household_name")) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { saveHouseholdName() }),
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = theme.ink, unfocusedTextColor = theme.ink, cursorColor = accent,
                    ),
                )
                if (trimmedName.isNotEmpty() && trimmedName != savedName) Button(
                    onClick = ::saveHouseholdName, enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                ) { Text(L("common.save")) }
            }
        }
        SetCaption(L("members.household_name.hint"))
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Button(
            onClick = {
                busy = true
                scope.launch {
                    attempt {
                        val invite = api.invite()
                        invites = listOf(invite) + invites
                        shown = Shown(invite.code, strings("members.invite_someone"), invite.expiresAt, null)
                    }
                    busy = false
                }
            },
            enabled = !busy, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
        ) { Text(L("members.invite_someone"), fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        SetCaption(L("members.invite_someone.hint"))
    }
    if (invites.isNotEmpty()) SetGroup(L("members.pending")) {
        SetCard {
            invites.forEachIndexed { i, invite ->
                if (i > 0) SetDivider()
                IndexRow("envelope", formatInviteCode(invite.code), validUntil(invite.expiresAt)) {
                    shown = Shown(invite.code, strings("members.invite_someone"), invite.expiresAt, null)
                }
            }
        }
    }
    SetGroup(L("members.list")) {
        SetCard {
            members.forEachIndexed { i, m ->
                if (i > 0) SetDivider()
                androidx.compose.runtime.CompositionLocalProvider(LocalAccent provides hexColor(m.avatarColor)) {
                    IndexRow(if (m.role == Role.Admin) "trophy" else "person", m.displayName) { editing = m }
                }
            }
        }
        SetCaption(L("members.list.hint"))
    }
    error?.let { Text(it, fontSize = 12.sp, color = Color(0xFFC0392B)) }

    editing?.let { m ->
        MemberSheet(
            api, m, isSelf = m.id == me.id,
            onSaved = { updated -> members = members.map { if (it.id == updated.id) updated else it } },
            // One dialog at a time: this one goes, the code's comes.
            onDeviceCode = { editing = null; showDeviceCode(m) },
            onDeleted = { members = members.filterNot { it.id == m.id }; editing = null },
            onDismiss = { editing = null },
        )
    }

    askWhy?.let { m ->
        CodeWhyDialog(m, onDismiss = { askWhy = null }, onRequest = {
            askWhy = null
            scope.launch { makeDeviceCode(m, DeviceCodePurpose.MemberRequest, null) }
        }, onEntry = { askWhy = null; reasonRefused = null; reasonFor = m })
    }
    reasonFor?.let { m ->
        CodeReasonDialog(m, refused = reasonRefused, onDismiss = { reasonFor = null; reasonRefused = null }) { reason ->
            reasonFor = null; reasonRefused = null
            scope.launch { makeDeviceCode(m, DeviceCodePurpose.AdminEntry, reason) }
        }
    }

    shown?.let { s ->
        InviteDialog(Invitation(household.serverUrl, s.code), s.title, s.expiresAt, onRevoke = {
            scope.launch {
                attempt {
                    if (s.memberId != null) api.revokeDeviceCode(s.memberId) else api.revokeInvite(s.code)
                    invites = invites.filterNot { it.code == s.code }
                    shown = null
                }
            }
        }) { shown = null }
    }
}

/**
 * One member, as the admin sees them (`MemberSheet`): the name and colour
 * everyone sees, a new device (the way in after a forgotten PIN — the admin
 * never sets someone's PIN from here, and the member is told a code let a
 * device in), and, for anyone but oneself, deleting the account.
 */
@Composable
private fun MemberSheet(
    api: SettingsApi, member: Member, isSelf: Boolean, onSaved: (Member) -> Unit, onDeviceCode: () -> Unit,
    onDeleted: () -> Unit, onDismiss: () -> Unit,
) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(member.displayName) }
    var color by remember { mutableStateOf(member.avatarColor) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // The colours on offer, as when they joined — plus theirs, if the console gave them another.
    val colours = eu.chezmaurice.maurice.onboarding.PROFILE_COLOURS.let { base ->
        if (base.any { it.equals(member.avatarColor, ignoreCase = true) }) base else listOf(member.avatarColor) + base
    }
    val trimmed = name.trim()
    val changed = trimmed != member.displayName || !color.equals(member.avatarColor, ignoreCase = true)

    fun run(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { error = e.shownMessage() }
            busy = false
        }
    }
    fun save() {
        if (!changed || trimmed.isEmpty() || busy) return
        run { onSaved(api.updateMember(member.id, trimmed, color)); onDismiss() }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 380.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(theme.surface).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(hexColor(color)), contentAlignment = Alignment.Center) {
                Text(trimmed.take(1).uppercase(), style = wordmark(24.sp), color = Color.White)
            }
            androidx.compose.material3.OutlinedTextField(
                name, { name = it }, singleLine = true, label = { Text(L("member.name")) },
                modifier = Modifier.widthIn(max = 260.dp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { save() }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (hex in colours) {
                    val picked = color.equals(hex, ignoreCase = true)
                    Box(
                        Modifier.size(32.dp).clip(CircleShape)
                            .border(2.dp, if (picked) theme.ink else Color.Transparent, CircleShape)
                            .clickable { color = hex }.padding(3.dp).clip(CircleShape).background(hexColor(hex)),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onDeviceCode) { Text(L("members.new_device"), color = legible(accent, theme.isDark)) }
                Text(L("member.new_device.hint"), fontSize = 12.sp, color = theme.inkMute, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            error?.let { Text(it, fontSize = 13.sp, color = Color(0xFFC0392B)) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss) { Text(L("common.cancel"), color = theme.ink) }
                Button(
                    onClick = ::save, enabled = !busy && trimmed.isNotEmpty() && changed,
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                ) { Text(L("common.save")) }
            }
            if (!isSelf) androidx.compose.material3.TextButton(onClick = { confirmDelete = true }, enabled = !busy) {
                Text(L("member.delete"), fontSize = 13.sp, color = Color(0xFFC0392B))
            }
        }
    }
    if (confirmDelete) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(L("member.delete.confirm", member.displayName)) },
        text = { Text(L("member.delete.hint")) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { confirmDelete = false; run { api.deleteMember(member.id); onDeleted() } }) {
                Text(L("member.delete"), color = Color(0xFFC0392B))
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text(L("common.cancel")) } },
        containerColor = theme.surface,
    )
}

/**
 * "Why this code?" (`SettingsView` `members.code_why`): the member will read
 * it in their settings. They asked for it, or the admin goes in themselves —
 * then [CodeReasonDialog].
 */
@Composable
private fun CodeWhyDialog(member: Member, onDismiss: () -> Unit, onRequest: () -> Unit, onEntry: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L("members.code_why.title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(L("members.code_why.message", member.displayName), color = theme.inkSoft)
                Spacer(Modifier.heightIn(min = 6.dp))
                androidx.compose.material3.TextButton(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                    Text(L("members.code_why.request"), color = legible(accent, theme.isDark), modifier = Modifier.fillMaxWidth())
                }
                androidx.compose.material3.TextButton(onClick = onEntry, modifier = Modifier.fillMaxWidth()) {
                    Text(L("members.code_why.entry"), color = legible(accent, theme.isDark), modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(L("common.cancel")) } },
        containerColor = theme.surface,
    )
}

/**
 * The admin goes into the account: the reason, in a sentence, required
 * before the code is made. [refused]: the server's words when it wanted one.
 */
@Composable
private fun CodeReasonDialog(member: Member, refused: String?, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    var reason by remember { mutableStateOf("") }
    val trimmed = reason.trim()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L("members.code_why.reason_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L("members.code_why.message", member.displayName), color = theme.inkSoft)
                androidx.compose.material3.OutlinedTextField(
                    reason, { reason = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(L("members.code_why.reason_placeholder")) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (trimmed.isNotEmpty()) onCreate(trimmed) }),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = theme.ink, unfocusedTextColor = theme.ink, cursorColor = accent, focusedBorderColor = accent,
                    ),
                )
                refused?.let { Text(it, fontSize = 12.sp, color = Color(0xFFC0392B)) }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onCreate(trimmed) }, enabled = trimmed.isNotEmpty()) {
                Text(L("members.code_why.create"))
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(L("common.cancel")) } },
        containerColor = theme.surface,
    )
}

/** "Valid for 4:32" when the code lapses within the hour, else null. */
@OptIn(ExperimentalTime::class)
@Composable
private fun remaining(iso: String, now: kotlin.time.Instant): String? {
    val left = ((parseServerDate(iso) ?: return null) - now).inWholeSeconds
    if (left <= 0 || left >= 3_600) return null
    return L("devices.add.countdown", "${left / 60}:${(left % 60).toString().padStart(2, '0')}")
}

/** "Valid until 2/10/2026", or "Valid until 14:32" for a code that lasts less than a day. */
@OptIn(ExperimentalTime::class)
@Composable
private fun validUntil(iso: String): String? {
    val instant = parseServerDate(iso) ?: return null
    val date = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val soon = instant - kotlin.time.Clock.System.now() < kotlin.time.Duration.parse("24h")
    val text = if (soon) "${date.hour}:${date.minute.toString().padStart(2, '0')}" else "${date.day}/${date.month.ordinal + 1}/${date.year}"
    return L("members.valid_until", text)
}

/**
 * The invitation as a QR code big enough to scan across a table, the code to
 * type, the link to send. [onRevoke] null for a code that cannot be withdrawn
 * (a member's own, minutes long). [watch], asked every two seconds for the
 * code's state (a member's own code): the dialog says when the new device is
 * in and closes, or says when the code lapsed — `InviteQRSheet`. [lines],
 * under the title: what the code gives (a friend's guest invitation).
 * [forBrowser]: no QR (a browser does not scan), the link written out with a
 * button that copies it.
 */
@Composable
internal fun InviteDialog(
    invitation: Invitation, title: String, expiresAt: String, onRevoke: (() -> Unit)?,
    watch: (suspend () -> String?)? = null, lines: List<String> = emptyList(), forBrowser: Boolean = false,
    onDismiss: () -> Unit,
) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val share = rememberShareText()
    val link = remember(invitation) { eu.chezmaurice.core.session.MauriceInvitation.link(invitation.serverUrl, invitation.code) }
    val matrix = remember(link) { qrMatrix(link) }
    var state by remember(invitation.code) { mutableStateOf("pending") }
    if (watch != null) LaunchedEffect(invitation.code) {
        while (state == "pending") {
            kotlinx.coroutines.delay(2_000)
            watch()?.takeIf { it != "pending" }?.let { state = it }
        }
        if (state == "used") { kotlinx.coroutines.delay(1_500); onDismiss() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 380.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(theme.surface).padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = wordmark(20.sp), color = theme.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            for (line in lines) Text(line, fontSize = 13.sp, color = theme.inkSoft, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            when (state) {
                "used" -> Text("✓  " + L("devices.add.done"), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = theme.ink,
                    modifier = Modifier.heightIn(min = 200.dp).wrapContentHeight())
                "expired" -> Text(L("devices.add.expired"), fontSize = 15.sp, color = theme.inkMute,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.heightIn(min = 200.dp).wrapContentHeight())
                else -> InviteCode(invitation, if (forBrowser) null else matrix, expiresAt, onRevoke, share, accent, forBrowser)
            }
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White)) { Text(L("common.done")) }
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun InviteCode(
    invitation: Invitation, matrix: List<BooleanArray>?, expiresAt: String, onRevoke: (() -> Unit)?,
    share: (String) -> Unit, accent: Color, forBrowser: Boolean,
) {
    val theme = LocalMauriceTheme.current
    val link = eu.chezmaurice.core.session.MauriceInvitation.link(invitation.serverUrl, invitation.code)
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    if (copied) LaunchedEffect(Unit) { kotlinx.coroutines.delay(1_800); copied = false }
    var now by remember { mutableStateOf(kotlin.time.Clock.System.now()) }
    LaunchedEffect(expiresAt) { while (true) { kotlinx.coroutines.delay(1_000); now = kotlin.time.Clock.System.now() } }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (forBrowser) SelectionContainer {
                Text(link, fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = theme.inkSoft,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(14.dp)).background(theme.ink.copy(alpha = 0.06f)).padding(horizontal = 14.dp, vertical = 12.dp))
            }
            // Dark modules on white, whatever the theme: a scanner wants contrast.
            if (matrix != null) Box(Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(14.dp).accessibilityLabel(link, button = false)) {
                Canvas(Modifier.fillMaxSize()) {
                    val cell = size.width / matrix.size
                    matrix.forEachIndexed { y, row -> row.forEachIndexed { x, dark ->
                        if (dark) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                    } }
                }
            }
            SelectionContainer { Text(formatInviteCode(invitation.code), fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = 3.sp, color = theme.ink) }
            // Within the hour (a member's own code lasts minutes), the time left, counting down; further off, the date.
            (remaining(expiresAt, now) ?: validUntil(expiresAt))?.let { Text(it, fontSize = 12.sp, color = theme.inkMute) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (forBrowser) OutlinedButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(link)); copied = true }) {
                    Text(L(if (copied) "browser.connect.copied" else "browser.connect.copy"), color = legible(accent, theme.isDark))
                }
                OutlinedButton(onClick = { share(link) }) { Text(L("members.share"), color = legible(accent, theme.isDark)) }
                if (onRevoke != null) OutlinedButton(onClick = onRevoke) { Text(L("members.revoke"), color = Color(0xFFC0392B)) }
            }
    }
}

// ── The photo, reframed ────────────────────────────────────

/**
 * Move and scale a photo inside the circle it will be shown in
 * (`AvatarCropView`), then hand back that square as a JPEG.
 */
@Composable
fun AvatarCrop(jpeg: ByteArray, onCancel: () -> Unit, onConfirm: (ByteArray) -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val source = remember(jpeg) { runCatching { jpeg.decodeToImageBitmap() }.getOrNull() } ?: return onCancel()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 380.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(theme.surface).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(L("settings.avatar.reframe"), style = wordmark(21.sp), color = theme.ink)
            Box(
                Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f).clip(CircleShape).border(1.dp, theme.ruleHard, CircleShape)
                    .onSizeChanged { box = it }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset += pan
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) { drawPhoto(source, size.width, scale, offset) }
            }
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("−", color = theme.inkSoft)
                Slider(scale, { scale = it }, valueRange = 1f..5f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = theme.rule))
                Text("+", color = theme.inkSoft)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(L("common.cancel"), color = theme.ink) }
                Button(
                    onClick = {
                        // The circle's square, drawn again at 840 px: the offset scales with it.
                        val out = 840
                        val k = if (box.width > 0) out.toFloat() / box.width else 1f
                        val bitmap = ImageBitmap(out, out)
                        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bitmap), Size(out.toFloat(), out.toFloat())) {
                            drawPhoto(source, out.toFloat(), scale, offset * k)
                        }
                        encodeJpeg(bitmap, 90)?.let(onConfirm) ?: onCancel()
                    },
                    modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                ) { Text(L("settings.avatar.use")) }
            }
        }
    }
}

/** The photo filling a square of side [side], scaled and moved. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPhoto(source: ImageBitmap, side: Float, scale: Float, offset: Offset) {
    val fill = max(side / source.width, side / source.height) * scale
    val w = source.width * fill
    val h = source.height * fill
    drawImage(
        source,
        dstOffset = IntOffset(((side - w) / 2 + offset.x).toInt(), ((side - h) / 2 + offset.y).toInt()),
        dstSize = IntSize(w.toInt(), h.toInt()),
    )
}
