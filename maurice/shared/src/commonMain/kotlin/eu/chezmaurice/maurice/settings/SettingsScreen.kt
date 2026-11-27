package eu.chezmaurice.maurice.settings

import eu.chezmaurice.core.net.shownMessage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.chezmaurice.core.model.Household
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.model.Role
import eu.chezmaurice.core.session.Prefs
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.maurice.absoluteUrl
import eu.chezmaurice.maurice.chat.BackHandlerCompat
import eu.chezmaurice.maurice.chat.legible
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.maurice.onboarding.PairingScreen
import eu.chezmaurice.maurice.platform.plainTextClip
import eu.chezmaurice.ui.Avatar
import eu.chezmaurice.ui.BoaterHat
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.Palette
import eu.chezmaurice.ui.Palettes
import eu.chezmaurice.ui.hexColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import eu.chezmaurice.core.settings.SettingsApi
import eu.chezmaurice.core.settings.OpenInvite
import eu.chezmaurice.core.settings.GuestInvite
import eu.chezmaurice.core.session.Invitation
import eu.chezmaurice.maurice.budget.BudgetPane
import eu.chezmaurice.maurice.budget.BudgetRing
import eu.chezmaurice.maurice.budget.LocalBudget
import eu.chezmaurice.maurice.budget.budgetValue
import eu.chezmaurice.maurice.store.LocalBilling
import eu.chezmaurice.maurice.store.LocalStore
import eu.chezmaurice.maurice.store.SubscriptionPane
import eu.chezmaurice.maurice.store.subscriptionValue

/** The detail panes, one tap from the index. */
enum class Pane { Appearance, Language, Household, Devices, Members, AdminPassword, Reports, Garden, Token, Files, Import, Mail, Contacts, Tools, MyData, Budget, Subscription, Safety }

private val LOCALES = listOf(null to "", "en" to "English", "fr" to "Français", "it" to "Italiano", "de" to "Deutsch", "es" to "Español", "pt" to "Português", "nl" to "Nederlands")

@Serializable private data class WebTheme(val id: String, val name: String = "")
@Serializable private data class WebThemes(val themes: List<WebTheme> = emptyList())

/**
 * Settings (`SettingsView`): who you are and your photo, which household,
 * language and look, your files, mail and history, your site and token,
 * your data, safety. A full page on a phone, a card on a wide window.
 */
@Composable
fun SettingsScreen(session: Session, household: Household, member: Member, initialPane: Pane? = null, onClose: () -> Unit) {
    // The budget ring and its toast open Settings on Budget; back still leads to the index.
    var pane by remember { mutableStateOf(initialPane) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandlerCompat { if (pane != null) pane = null else onClose() }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            val wide = maxWidth >= 720.dp
            val theme = LocalMauriceTheme.current
            Column(
                (if (wide) Modifier.widthIn(max = 460.dp).heightIn(max = 760.dp).clip(RoundedCornerShape(20.dp)).border(0.5.dp, theme.rule, RoundedCornerShape(20.dp))
                else Modifier).fillMaxSize().background(theme.surface),
            ) {
                val current = pane
                if (current == null) Root(session, household, member, onPane = { pane = it }, onClose = onClose)
                else {
                    SetDetailHeader(title(current), back = L("settings.title"), onBack = { pane = null }, onClose = onClose)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(top = 14.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        when (current) {
                            Pane.Appearance -> AppearancePane(session)
                            Pane.Language -> LanguagePane(session)
                            Pane.Household -> HouseholdPane(session, onClose)
                            Pane.Devices -> DevicesPane(session)
                            Pane.Garden -> GardenPane(session)
                            Pane.Token -> TokenPane(session)
                            Pane.Safety -> SafetyPane()
                            Pane.Members -> MembersPane(session, household, member)
                            Pane.AdminPassword -> AdminPasswordPane(session, member)
                            Pane.Reports -> ReportsPane(session)
                            Pane.Files -> FilesPane(session)
                            Pane.Import -> ImportPane(session)
                            Pane.Mail -> MailPane(session)
                            Pane.Contacts -> ContactsPane(session)
                            Pane.Tools -> ToolsPane(session)
                            Pane.MyData -> MyDataPane(session, member, onClose)
                            Pane.Budget -> LocalBudget.current?.let { budget ->
                                val store = LocalStore.current
                                val hasStore = store?.state?.collectAsState()?.value?.hasStore == true
                                BudgetPane(budget, appLanguage(session), openSubscription = if (hasStore) ({ pane = Pane.Subscription }) else null)
                            }
                            Pane.Subscription -> {
                                val store = LocalStore.current
                                val billing = LocalBilling.current
                                if (store != null && billing != null) SubscriptionPane(store, billing, appLanguage(session))
                            }
                        }
                    }
                    Footer(session, household)
                }
            }
        }
    }
}

@Composable
private fun title(p: Pane): String = when (p) {
    Pane.Appearance -> L("settings.palette.title")
    Pane.Language -> L("settings.locale.title")
    Pane.Household -> L("foyer.switch_title")
    Pane.Devices -> L("mydevices.title")
    Pane.Members -> L("members.title")
    Pane.AdminPassword -> L("password.title")
    Pane.Reports -> L("report.ai.queue.open")
    Pane.Garden -> L("settings.garden.title")
    Pane.Token -> L("settings.mcp.title")
    Pane.Files -> L("settings.files.title")
    Pane.Import -> L("settings.import.title")
    Pane.Mail -> L("mail.title")
    Pane.Contacts -> L("contacts.title")
    Pane.Tools -> L("mytools.title")
    Pane.MyData -> L("mydata.title")
    Pane.Budget -> L("budget.title")
    Pane.Subscription -> L("store.title")
    Pane.Safety -> "Safety"
}

private val APP_LANGUAGES = setOf("de", "en", "es", "fr", "it", "nl", "pt")

/** The language the interface speaks: the member's choice, else the device's, else English. */
@Composable
internal fun appLanguage(session: Session): String {
    val prefs by session.prefs.collectAsState()
    return prefs.locale?.takeIf { it in APP_LANGUAGES }
        ?: androidx.compose.ui.text.intl.Locale.current.language.takeIf { it in APP_LANGUAGES } ?: "en"
}

@Composable
private fun Root(session: Session, household: Household, member: Member, onPane: (Pane) -> Unit, onClose: () -> Unit) {
    val prefs by session.prefs.collectAsState()
    val state by session.state.collectAsState()
    val guest = member.role == Role.Guest
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val summary = rememberSettingsSummary(session, guest)
    var ownDeviceCode by remember { mutableStateOf<OpenInvite?>(null) }
    var ownDeviceError by remember { mutableStateOf<String?>(null) }
    // The same code for the browser extension, shown as a link to paste rather than a QR.
    var ownDeviceForBrowser by remember { mutableStateOf(false) }
    // A friend from another household, invited as a guest (GuestInvite): the row goes once the server says no.
    var guestCode by remember { mutableStateOf<GuestInvite.Code?>(null) }
    var guestRefused by remember { mutableStateOf(GuestInvite.isRefused(household.serverUrl, member.id)) }
    guestCode?.let { code ->
        InviteDialog(
            code.invitation(household.serverUrl), L("guest_invite.title"), code.expiresAt, onRevoke = null,
            lines = listOf(L("guest_invite.sees_only_you"), L("guest_invite.maurice")),
        ) { guestCode = null }
    }
    ownDeviceCode?.let { code ->
        val api = remember(code.code) { session.api()?.let(::SettingsApi) }
        InviteDialog(
            Invitation(household.serverUrl, code.code), L(if (ownDeviceForBrowser) "browser.connect" else "devices.add.sheet_title"), code.expiresAt, onRevoke = null,
            watch = { api?.ownDeviceCodeState(code.code) },
            lines = if (ownDeviceForBrowser) listOf(L("browser.connect.line")) else emptyList(), forBrowser = ownDeviceForBrowser,
        ) { ownDeviceCode = null }
    }
    Column(Modifier.fillMaxSize()) {
        SetHeader(L("settings.title"), onClose)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(top = 14.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Identity(session, household, member, onSwitch = { onClose(); session.switchMember() })

            // Which household, right under who: the two "who/where am I" together.
            SetGroup(L("foyer.switch_overline")) {
                SetCard {
                    IndexRow("house", L("foyer.switch_title"), household.name) { onPane(Pane.Household) }
                    // The devices signed in as this member, and signing one out from here (DevicesView).
                    SetDivider()
                    IndexRow("laptopcomputer.and.iphone", L("mydevices.title")) { onPane(Pane.Devices) }
                    // Any member but a guest brings in another device of theirs, as on iOS.
                    if (!guest) {
                        SetDivider()
                        fun addOwnDevice(browser: Boolean) = scope.launch {
                            ownDeviceError = null
                            val code = runCatching { session.api()?.let(::SettingsApi)?.ownDeviceCode() }
                                .onFailure { ownDeviceError = it.shownMessage() }.getOrNull()
                            if (code != null) { ownDeviceForBrowser = browser; ownDeviceCode = code }
                        }
                        IndexRow("plus.circle", L("devices.add")) { addOwnDevice(browser = false) }
                        // The "Save to Carnet" extension: the same code, pasted as a link rather than scanned.
                        SetDivider()
                        IndexRow("globe", L("browser.connect")) { addOwnDevice(browser = true) }
                        if (!guestRefused) {
                            SetDivider()
                            IndexRow("person.badge.plus", L("guest_invite.title")) {
                                scope.launch {
                                    ownDeviceError = null
                                    val api = session.api() ?: return@launch
                                    when (val outcome = GuestInvite.create(api, household.serverUrl, member.id)) {
                                        is GuestInvite.Outcome.Issued -> guestCode = outcome.code
                                        GuestInvite.Outcome.NotAllowed -> guestRefused = true
                                        is GuestInvite.Outcome.Failed -> ownDeviceError = outcome.error.shownMessage()
                                    }
                                }
                            }
                        }
                    }
                    if (member.role == Role.Admin) {
                        SetDivider(); IndexRow("person.2", L("members.title")) { onPane(Pane.Members) }
                        SetDivider(); IndexRow("lock.fill", L("password.title")) { onPane(Pane.AdminPassword) }
                        // The admin's queue, where replies of Maurice's reported from private conversations land too.
                        SetDivider(); IndexRow("exclamationmark.triangle.fill", L("report.ai.queue.open")) { onPane(Pane.Reports) }
                    }
                }
                ownDeviceError?.let { SetCaption(it) }
            }
            SetCard {
                IndexRow("globe", L("settings.locale.title"), LOCALES.firstOrNull { it.first == prefs.locale }?.second?.ifEmpty { null } ?: L("settings.locale.system")) { onPane(Pane.Language) }
                SetDivider()
                IndexRow("paintpalette", L("settings.palette.title"), appearanceValue(prefs)) { onPane(Pane.Appearance) }
            }
            // Guests have no library, mail, or history here.
            if (!guest) {
                SetGroup(L("settings.files.group")) {
                    SetCard { IndexRow("folder", L("settings.files.title"), summary.files) { onPane(Pane.Files) } }
                }
                SetGroup(L("mail.group")) {
                    SetCard {
                        IndexRow("envelope", L("mail.title"), summary.mail) { onPane(Pane.Mail) }
                        SetDivider()
                        IndexRow("person.crop.rectangle.stack", L("contacts.title"), summary.contacts) { onPane(Pane.Contacts) }
                    }
                    SetCaption(L("mail.caption"))
                }
                // Their own tools: MCP servers they connect themselves, which only their conversations hold.
                SetGroup(L("mytools.group")) {
                    SetCard { IndexRow("puzzlepiece.extension", L("mytools.title"), summary.tools) { onPane(Pane.Tools) } }
                    SetCaption(L("mytools.caption"))
                }
                SetGroup(L("settings.import.group")) {
                    SetCard { IndexRow("tray.full", L("settings.import.title"), summary.import) { onPane(Pane.Import) } }
                    SetCaption(L("settings.import.caption"))
                }
            }
            SetGroup(L("settings.garden.title")) {
                SetCard {
                    IndexRow("leaf", L("settings.garden.webtheme"), summary.webThemeLabel(prefs.webTheme)) { onPane(Pane.Garden) }
                    SetDivider()
                    IndexRow("globe", L("settings.garden.open"), L("settings.garden.signedin"), trailing = { Text("↗", color = legible(LocalAccent.current, LocalMauriceTheme.current.isDark)) }) {
                        scope.launch { session.signedInUrl(theme = prefs.webTheme)?.let(uri::openUri) }
                    }
                }
            }
            SetGroup(L("settings.mcp.title")) {
                SetCard { IndexRow("key", L("settings.mcp.token"), summary.maskedToken, valueMono = true) { onPane(Pane.Token) } }
                SetCaption(L("settings.mcp.copyhint"))
            }
            // What is left of the household's budget — never an amount here;
            // not shown when the household holds none.
            LocalBudget.current?.let { store ->
                val budget by store.state.collectAsState()
                if (budget.isLimited) SetGroup(L("budget.group")) {
                    SetCard { IndexRow("dial.medium", L("budget.title"), budgetValue(budget.left), trailing = { BudgetRing(budget.left, size = 18.dp) }) { onPane(Pane.Budget) } }
                }
            }
            // The admin of a hosted household subscribes and tops up here, in the
            // Play build; the store has no account for anyone else.
            LocalStore.current?.let { store ->
                val bought by store.state.collectAsState()
                if (bought.hasStore) SetGroup(L("store.group")) {
                    SetCard { IndexRow("creditcard", L("store.title"), subscriptionValue(bought)) { onPane(Pane.Subscription) } }
                }
            }
            TurnCostGroup(session.store)
            PulseGroup()
            // What the server holds of this member: theirs to take, bring back, or erase.
            SetGroup(L("mydata.group")) {
                // The orange dot while a line of the register of interventions is unread.
                val unread = (LocalInterventions.current?.unread ?: 0) > 0
                SetCard {
                    IndexRow("externaldrive", L("mydata.title"), trailing = if (unread) ({ InterventionDot(size = 8.dp) }) else null) { onPane(Pane.MyData) }
                }
                SetCaption(L("mydata.index_caption"))
            }
            SetGroup("Safety") {
                SetCard { IndexRow("shield", "Report a concern / Safety") { onPane(Pane.Safety) } }
                SetCaption("Report room content to the operator from a message's menu. To report this server, or for child-safety, use the options here.")
            }
        }
        Footer(session, household)
    }
    // The household name and members may have changed elsewhere.
    LaunchedEffect(state.currentId) { runCatching { session.refreshMembers() } }
}

/** The values the index rows show, loaded once when settings open. */
private class SettingsSummary {
    var files by mutableStateOf<String?>(null)
    var mail by mutableStateOf<String?>(null)
    var contacts by mutableStateOf<String?>(null)
    var tools by mutableStateOf<String?>(null)
    var import by mutableStateOf<String?>(null)
    var maskedToken by mutableStateOf("—")
    var themes by mutableStateOf(listOf("default" to "Default"))
    fun webThemeLabel(id: String) = themes.firstOrNull { it.first == id }?.second ?: id
}

@Composable
private fun rememberSettingsSummary(session: Session, guest: Boolean): SettingsSummary {
    val summary = remember { SettingsSummary() }
    val strings = eu.chezmaurice.maurice.domains.strings()
    LaunchedEffect(Unit) {
        val api = session.api() ?: return@LaunchedEffect
        launch { runCatching { session.mcpToken() }.getOrNull()?.let { summary.maskedToken = mask(it) } }
        launch {
            runCatching { api.get<WebThemes>("/api/web-themes") }.getOrNull()?.themes?.takeIf { it.isNotEmpty() }?.let { t ->
                summary.themes = t.map { it.id to it.name.ifEmpty { it.id } }
            }
        }
        if (guest) return@LaunchedEffect
        val settings = SettingsApi(api)
        launch { settings.library()?.summary?.let { summary.files = "${it.count} · ${formatBytes(it.sizeBytes)}" } }
        launch { settings.mailAccounts()?.let { summary.mail = if (it.isEmpty()) strings("mail.value.none") else strings("mail.value.count", it.size) } }
        launch { settings.contactAccounts()?.let { a -> summary.contacts = if (a.isEmpty()) strings("contacts.value.none") else strings("mail.value.count", a.sumOf { it.cards }) } }
        launch { eu.chezmaurice.core.settings.ToolServersApi(api).list()?.servers?.let { summary.tools = if (it.isEmpty()) strings("contacts.value.none") else strings("mail.value.count", it.size) } }
        launch { summary.import = strings(if (settings.imports()?.any { h -> h.watermark != null } == true) "settings.import.value.synced" else "settings.import.value.none") }
    }
    return summary
}

internal fun mask(token: String) = if (token.length > 8) "maur_****" + token.takeLast(8) else token

@Composable
private fun appearanceValue(prefs: Prefs): String {
    val mode = when {
        prefs.palette == "auto" -> L("settings.palette.auto")
        Palettes.dark.any { it.id == prefs.palette } -> L("settings.palette.mode_dark")
        else -> L("settings.palette.mode_light")
    }
    val label = Palettes.all.firstOrNull { it.id == prefs.palette }?.label
    return if (label != null) "$mode · $label" else mode
}

/** Who is here: the photo (tap to change), the name, the way to another member. */
@Composable
private fun Identity(session: Session, household: Household, member: Member, onSwitch: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    var cropping by remember { mutableStateOf<ByteArray?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // The uploader: a dashed halo and a camera badge say "tap to change".
        AvatarSource(!uploading, onPhoto = { cameraDenied = false; cropping = it }, onDenied = { cameraDenied = true }, Modifier.size(62.dp).clip(CircleShape)) {
            Box(Modifier.size(62.dp).eu_dashedCircle(theme.ruleHard))
            Avatar(member.displayName, member.avatarColor, absoluteUrl(household.serverUrl, member.avatarUrl), size = 50.dp)
            Box(
                Modifier.align(Alignment.BottomEnd).size(21.dp).clip(CircleShape).background(theme.surfaceAlt).border(0.5.dp, theme.ruleHard, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(DomainGlyphs.of("camera"), null, tint = theme.inkSoft, modifier = Modifier.size(12.dp)) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(member.displayName, fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = theme.ink)
                Spacer(Modifier.width(9.dp))
                if (member.role == Role.Guest) {
                    Text(L("settings.user.guest"), fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 1.sp, color = theme.inkSoft,
                        modifier = Modifier.clip(CircleShape).border(0.5.dp, theme.ruleHard, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp))
                } else {
                    Text(L("member.switch"), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = legible(accent, theme.isDark),
                        modifier = Modifier.clip(CircleShape).border(0.5.dp, theme.ruleHard, CircleShape).clickable(onClick = onSwitch).padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            SetCaption(L(if (cameraDenied) "settings.avatar.camera_denied" else "settings.avatar.hint"))
        }
    }
    cropping?.let { jpeg ->
        AvatarCrop(jpeg, onCancel = { cropping = null }) { square ->
            cropping = null
            uploading = true
            scope.launch {
                runCatching { session.uploadAvatar(eu.chezmaurice.core.media.PhotoSize.dataUri(square)) }
                uploading = false
            }
        }
    }
}

private fun Modifier.eu_dashedCircle(color: Color) = this.then(
    Modifier.border(1.dp, color.copy(alpha = 0.6f), CircleShape),
)

/** The server's address, and leaving it. */
@Composable
private fun Footer(session: Session, household: Household) {
    val theme = LocalMauriceTheme.current
    val scope = rememberCoroutineScope()
    Column {
        SetDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(household.serverUrl, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = theme.inkMute, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            // "Sign out": this member only; forgetting the household is in "Change household".
            androidx.compose.material3.TextButton(onClick = { scope.launch { session.signOut() } }) {
                Text(L("settings.disconnect"), fontSize = 14.sp, color = Color(0xFFC0392B))
            }
        }
    }
}

// ── Panes ──────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearancePane(session: Session) {
    val prefs by session.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    fun set(palette: String) = scope.launch { session.setPrefs(prefs.copy(palette = palette)) }
    val mode = when {
        prefs.palette == "auto" -> "auto"
        Palettes.dark.any { it.id == prefs.palette } -> "dark"
        else -> "light"
    }
    eu.chezmaurice.maurice.composer.Segmented(
        listOf("auto" to L("settings.palette.auto"), "light" to L("settings.palette.mode_light"), "dark" to L("settings.palette.mode_dark")), mode,
    ) { m ->
        when (m) {
            "auto" -> set("auto")
            "light" -> if (Palettes.light.none { it.id == prefs.palette }) set("cream")
            "dark" -> if (Palettes.dark.none { it.id == prefs.palette }) set("studio")
        }
    }
    for ((kicker, palettes) in listOf(L("settings.palette.light") to Palettes.light, L("settings.palette.dark") to Palettes.dark)) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            SetKicker(kicker)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in palettes) Swatch(p, prefs.palette == p.id) { set(p.id) }
            }
        }
    }
    SetCaption(L("settings.palette.auto_hint"))
}

@Composable
private fun Swatch(p: Palette, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    Column(Modifier.width(58.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val ink = if (p.isDark) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.5f)
        Box(
            Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(6.dp)).background(p.surface)
                .border(if (selected) 2.dp else 0.5.dp, if (selected) accent else if (p.isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.10f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.5.dp)) {
                Box(Modifier.size(20.dp, 2.dp).background(ink))
                Box(Modifier.size(14.dp, 1.5.dp).background(ink.copy(alpha = 0.5f)))
            }
        }
        Text(p.label, fontSize = 9.sp, color = if (selected) theme.ink else theme.inkMute, maxLines = 1)
    }
}

@Composable
private fun LanguagePane(session: Session) {
    val prefs by session.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    SetCard {
        LOCALES.forEachIndexed { i, (id, label) ->
            SetCheckRow(label.ifEmpty { L("settings.locale.system") }, prefs.locale == id) { scope.launch { session.setPrefs(prefs.copy(locale = id)) } }
            if (i < LOCALES.lastIndex) SetDivider()
        }
    }
}

/** Every paired household, the active one checked, and pairing another. */
@Composable
private fun HouseholdPane(session: Session, onClose: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val state by session.state.collectAsState()
    var adding by remember { mutableStateOf(false) }
    SetCard {
        for (h in state.households) {
            val active = h.id == state.currentId
            Row(
                Modifier.fillMaxWidth().heightIn(min = 50.dp).background(if (active) accent.copy(alpha = 0.08f) else Color.Transparent)
                    .clickable { onClose(); if (!active) session.selectHousehold(h.id) }.padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(hexColor(h.color, theme.inkSoft).copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    BoaterHat(size = 18.dp, color = hexColor(h.color, theme.ink))
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(h.name, fontSize = 13.5.sp, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(h.serverUrl.substringAfter("://").trimEnd('/'), fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = theme.inkMute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Its unread conversations, in the household's colour (HouseholdRow on iOS).
                h.unread?.takeIf { it > 0 }?.let { n ->
                    Box(Modifier.padding(start = 8.dp).heightIn(min = 17.dp).widthIn(min = 17.dp).clip(RoundedCornerShape(50)).background(hexColor(h.color, theme.inkSoft)).padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center) {
                        Text("$n", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color.White)
                    }
                    if (active) Spacer(Modifier.width(8.dp))
                }
                if (active) Icon(Icons.Default.Check, null, tint = legible(accent, theme.isDark), modifier = Modifier.size(16.dp))
            }
            SetDivider()
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).clickable { adding = true }.padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).border(1.dp, theme.ruleHard, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                Icon(DomainGlyphs.of("plus"), null, tint = theme.inkSoft, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(11.dp))
            Text(L("household.add"), fontSize = 13.5.sp, color = theme.ink)
        }
    }
    // Forget the household on screen: every member here signed out, their data erased.
    state.current?.let { current ->
        var forgetting by remember { mutableStateOf(false) }
        androidx.compose.material3.TextButton(onClick = { forgetting = true }) {
            Text(L("picker.disconnect"), color = Color(0xFFC0392B), fontSize = 14.sp)
        }
        if (forgetting) eu.chezmaurice.maurice.onboarding.ForgetHouseholdDialog(session, current) { forgetting = false }
    }
    if (adding) Dialog(onDismissRequest = { adding = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(theme.bg)) { PairingScreen(session, onPaired = { adding = false }, onCancel = { adding = false }) }
    }
}

/** The member's site: open it signed in, and choose how it looks. */
@Composable
private fun GardenPane(session: Session) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val prefs by session.prefs.collectAsState()
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var themes by remember { mutableStateOf(listOf("default" to "Default")) }
    LaunchedEffect(Unit) {
        session.api()?.let { api -> runCatching { api.get<WebThemes>("/api/web-themes") }.getOrNull()?.themes?.takeIf { it.isNotEmpty() }?.let { t -> themes = t.map { it.id to it.name.ifEmpty { it.id } } } }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(theme.surfaceAlt).border(0.5.dp, theme.rule, RoundedCornerShape(12.dp))
            .clickable { scope.launch { session.signedInUrl(theme = prefs.webTheme)?.let(uri::openUri) } }.padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(DomainGlyphs.of("leaf"), null, tint = legible(accent, theme.isDark), modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(L("settings.garden.openlink"), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = theme.ink)
            // Where it lives: host/g/<username> (`gardenHost`).
            val state by session.state.collectAsState()
            val server = state.current?.serverUrl.orEmpty()
            val slug = state.activeMember?.let { it.username ?: it.id }
            val host = runCatching { io.ktor.http.Url(server).host }.getOrNull()?.takeIf { it.isNotEmpty() }
            Text(
                if (host != null && slug != null) "$host/g/$slug" else server,
                fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, color = theme.inkMute, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
            )
        }
        Text(
            L("settings.garden.openbutton") + " ↗", fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = Color.White,
            modifier = Modifier.clip(CircleShape).background(accent).padding(horizontal = 11.dp, vertical = 6.dp),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SetKicker(L("settings.garden.webtheme"))
        SetCard {
            themes.forEachIndexed { i, (id, label) ->
                SetCheckRow(label, prefs.webTheme == id) { scope.launch { session.setPrefs(prefs.copy(webTheme = id)) } }
                if (i < themes.lastIndex) SetDivider()
            }
        }
        SetCaption(L("settings.garden.webtheme_hint"))
    }
}

/** The `maur_` token: shown masked, copied, rolled; and the web signed in with it. */
@Composable
private fun TokenPane(session: Session) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val clipboard = LocalClipboard.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { token = runCatching { session.mcpToken() }.getOrNull() }
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(theme.surfaceAlt).border(0.5.dp, theme.rule, RoundedCornerShape(10.dp)).padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(token?.let(::mask) ?: L("settings.mcp.loading"), fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, color = theme.ink, modifier = Modifier.weight(1f))
        Icon(
            if (copied) Icons.Default.Check else eu.chezmaurice.ui.MauriceIcons.ContentCopy, L("chat.copy"),
            tint = if (copied) Color(0xFF3D6B4F) else theme.inkSoft,
            modifier = Modifier.size(32.dp).clip(CircleShape).clickable(enabled = token != null) {
                scope.launch { clipboard.setClipEntry(plainTextClip(token!!)); copied = true; delay(1_800); copied = false }
            }.padding(7.dp),
        )
        Icon(
            Icons.Default.Refresh, null, tint = theme.inkSoft,
            modifier = Modifier.size(32.dp).clip(CircleShape).clickable { token = null; scope.launch { token = runCatching { session.mcpToken(rotate = true) }.getOrNull() } }.padding(7.dp),
        )
    }
    SetCaption(L("settings.mcp.hint"))
    Text(
        L("settings.mcp.open_browser") + " ↗", fontSize = 12.sp, color = legible(accent, theme.isDark),
        modifier = Modifier.clickable { scope.launch { session.signedInUrl()?.let(uri::openUri) } },
    )
}

/**
 * The out-of-band safety channel (`SafetySettingsView`): links out to those
 * who can act. Reporting a message of a room is in the message's menu.
 */
@Composable
private fun SafetyPane() {
    val theme = LocalMauriceTheme.current
    val accent = legible(LocalAccent.current, theme.isDark)
    val uri = LocalUriHandler.current
    @Composable fun link(label: String, url: String) =
        Text(label, fontSize = 13.5.sp, color = accent, modifier = Modifier.fillMaxWidth().clickable { uri.openUri(url) }.padding(horizontal = 13.dp, vertical = 12.dp))
    SetGroup("In a room") {
        SetCaption("In a shared room, open a message's menu to report it to this server's operator, or to block a member. To stop receiving a room, use the room's Leave — that works regardless of the operator.")
    }
    SetGroup("Child safety") {
        SetCard {
            link("Child Focus — Belgium (stopchildporno.be)", "https://www.stopchildporno.be")
            SetDivider()
            link("NCMEC CyberTipline — United States", "https://report.cybertip.org")
        }
        SetCaption("Content involving a minor: report to the proper authority — they can act in ways an operator can't. Outside Belgium or the US, contact your local equivalent.")
    }
    SetGroup("App contact") {
        SetCard { link("safety@chezmaurice.eu", "mailto:safety@chezmaurice.eu") }
        SetCaption("Questions about the Chez Maurice app itself. This is the publisher's contact, not an abuse desk — for abuse, use the options above.")
    }
    SetGroup("Legal") {
        SetCard {
            link("Terms of Use", "https://www.chezmaurice.eu/terms.html")
            SetDivider()
            link("Privacy Policy", "https://www.chezmaurice.eu/privacy.html")
        }
    }
}

/** "1.2 MB", for the library. */
internal fun formatBytes(n: Long): String {
    // As the iOS app counts: by 1024, one decimal under 100.
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = n.toDouble(); var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    if (i == 0) return "$n B"
    val shown = if (v >= 100) kotlin.math.round(v).toLong().toString() else (kotlin.math.round(v * 10) / 10.0).toString()
    return "$shown ${units[i]}"
}

internal fun formatBytesForTest(n: Long) = formatBytes(n)
