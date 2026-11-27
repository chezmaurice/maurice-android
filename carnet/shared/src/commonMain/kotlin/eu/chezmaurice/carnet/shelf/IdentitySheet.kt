package eu.chezmaurice.carnet.shelf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.chezmaurice.carnet.ui.MemberBadge
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.settings.SettingsApi
import eu.chezmaurice.maurice.absoluteUrl
import eu.chezmaurice.maurice.onboarding.ForgetHouseholdDialog
import eu.chezmaurice.maurice.onboarding.PairingScreen
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.wordmark
import eu.chezmaurice.ui.LocalMauriceTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Who is here, and where (`MemberSwitchSheet`): the households, the members
 * (another one goes through the member picker, PIN and all), adding a
 * household, signing out, forgetting the household, privacy, deleting one's
 * account. The same words as the Maurice app, in the member's language.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentitySheet(session: Session, onDismiss: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsState()
    val household = state.current ?: return
    var pairing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    var invitingGuest by remember { mutableStateOf(false) }
    var connectingBrowser by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = theme.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(household.name, style = wordmark(22.sp), color = theme.ink, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(L("common.done"), color = theme.ink) }
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (state.households.size > 1) {
                    Section(L("sheet.households"))
                    for (h in state.households) {
                        val current = h.id == household.id
                        ActionRow("house", h.name, trailing = if (current) L("sheet.active") else null, bold = current) { onDismiss(); if (!current) session.selectHousehold(h.id) }
                    }
                }
                Section(L("sheet.members"))
                for (m in household.members) {
                    val active = m.id == household.activeMemberId
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onDismiss()
                            // Another member: the picker, which asks for their PIN.
                            if (!active) session.switchMember()
                        }.padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MemberBadge(m.displayName, m.avatarColor, absoluteUrl(household.serverUrl, m.avatarUrl), 36.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.displayName, fontSize = 16.sp, color = theme.ink)
                            if (m.hasPin) Text(L("sheet.pin_required"), fontSize = 12.sp, color = theme.inkMute)
                        }
                        if (active) Pill(L("sheet.you"))
                    }
                }
                ActionRow("plus", L("household.add")) { pairing = true }
                // The « Save to Carnet » extension of a browser, signed in as this member.
                ActionRow("globe", L("browser.connect")) { connectingBrowser = true }
                Section(null)
                // A guest may not invite; a child only when the admin allowed it, which the server says.
                if (state.activeMember?.role != eu.chezmaurice.core.model.Role.Guest) {
                    ActionRow("person.badge.plus", L("guest.invite")) { invitingGuest = true }
                }
                ActionRow("person", L("settings.disconnect")) { onDismiss(); scope.launch { session.signOut() } }
                ActionRow("minus.circle", L("picker.disconnect"), tint = Color(0xFFC0392B)) { forgetting = true }
                Section(L("privacy.section"))
                ActionRow("hand.raised", L("privacy.policy")) { uri.openUri("https://www.chezmaurice.eu/privacy.html") }
                ActionRow("doc.text", L("privacy.terms")) { uri.openUri("https://www.chezmaurice.eu/terms.html") }
                ActionRow("person", L("delete.confirm") + "…", tint = Color(0xFFC0392B)) { deleting = true }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
    if (pairing) Dialog(onDismissRequest = { pairing = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(theme.bg)) { PairingScreen(session) }
    }
    if (invitingGuest) GuestInviteSheet(session) { invitingGuest = false }
    if (connectingBrowser) BrowserConnectDialog(session, household.serverUrl) { connectingBrowser = false }
    if (forgetting) ForgetHouseholdDialog(session, household) { forgetting = false; onDismiss() }
    if (deleting) DeleteAccount(session, onDismiss = { deleting = false }) { onDismiss() }
}

/** A group's title; null for a gap between groups. */
@Composable
private fun Section(text: String?) {
    val theme = LocalMauriceTheme.current
    if (text == null) Spacer(Modifier.size(12.dp))
    else Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = theme.inkMute, modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 4.dp))
}

/** "You", "Active": a tonal pill, as Material 3 marks a state. */
@Composable
private fun Pill(text: String) {
    val theme = LocalMauriceTheme.current
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = theme.ink,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(theme.ink.copy(alpha = 0.08f)).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
private fun ActionRow(symbol: String, text: String, tint: Color = LocalMauriceTheme.current.ink, trailing: String? = null, bold: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(DomainGlyphs.of(symbol), null, tint = tint.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, fontSize = 16.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, color = tint, modifier = Modifier.weight(1f))
        if (trailing != null) Pill(trailing)
    }
}

/** Erase everything, then the account: confirmed by typing one's username, and the password or PIN. */
@Composable
private fun DeleteAccount(session: Session, onDismiss: () -> Unit, onDone: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsState()
    val household = state.current ?: return
    val member = state.activeMember ?: return
    val username = member.username.orEmpty()
    val strings = eu.chezmaurice.ui.LocalStrings.current
    var confirm by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val ready = !busy && username.isNotEmpty() && confirm.trim() == username && secret.isNotEmpty()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 440.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(theme.surface).imePadding().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(L("delete.confirm"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = theme.ink)
            Text(L("delete.text"), fontSize = 14.sp, color = theme.inkSoft)
            OutlinedTextField(confirm, { confirm = it }, label = { Text(username) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                secret, { secret = it }, label = { Text(L("delete.field")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (username.isNotEmpty()) Text(L("delete.confirm_footer", username), fontSize = 12.sp, color = theme.inkMute)
            failure?.let { Text(it, fontSize = 12.sp, color = Color(0xFFC0392B)) }
            Row {
                TextButton(onClick = onDismiss) { Text(L("common.cancel"), color = theme.inkSoft) }
                Spacer(Modifier.weight(1f))
                TextButton(enabled = ready, onClick = {
                    busy = true; failure = null
                    scope.launch {
                        try {
                            val api = session.api() ?: return@launch
                            SettingsApi(api).erase("account", confirm.trim(), secret)
                            session.disconnectMember(household.id, member.id)
                            onDone()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            val reason = (e as? ApiError)?.message
                            failure = if (reason in listOf("confirm_mismatch", "bad_password", "bad_pin", "last_admin", "too_many_attempts", "session_required"))
                                strings("mydata.error.$reason") else e.message
                        }
                        busy = false
                    }
                }) {
                    if (busy) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp); Spacer(Modifier.width(6.dp)) }
                    Text(L("delete.confirm"), color = if (ready) Color(0xFFC0392B) else theme.inkMute)
                }
            }
        }
    }
}
