package eu.chezmaurice.maurice.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.chezmaurice.core.model.formatServerDate
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.settings.ContactAccount
import eu.chezmaurice.core.settings.MailAccount
import eu.chezmaurice.core.settings.MailDocuments
import eu.chezmaurice.core.settings.MailScan
import eu.chezmaurice.core.settings.Mailbox
import eu.chezmaurice.core.settings.Sender
import eu.chezmaurice.core.settings.SettingsApi
import eu.chezmaurice.maurice.chat.legible
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.maurice.domains.strings
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.Strings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val WARM = Color(0xFFB97A1E)

/** The mail provider an address belongs to, and what it takes to connect it (`MailProvider`). */
private enum class MailProvider(val help: String, val page: Pair<String, String>? = null) {
    Gmail("mail.help.gmail", "mail.help.gmail.open" to "https://myaccount.google.com/apppasswords"),
    Icloud("mail.help.icloud", "mail.help.icloud.open" to "https://account.apple.com"),
    Yahoo("mail.help.yahoo", "mail.help.yahoo.open" to "https://login.yahoo.com/account/security"),
    Fastmail("mail.help.fastmail"), Outlook("mail.help.outlook"), Proton("mail.help.proton"), Isp("mail.help.isp"), Unknown("mail.help.unknown");

    /** Outlook allows no app passwords any more. */
    val possible get() = this != Outlook
    val wantsAppPassword get() = this in setOf(Gmail, Icloud, Yahoo, Fastmail)

    companion object {
        fun of(address: String): MailProvider? {
            val domain = address.substringAfterLast('@', "").lowercase()
            if (!address.contains('@') || !domain.contains('.') || domain.endsWith(".")) return null
            return when (domain) {
                "gmail.com", "googlemail.com" -> Gmail
                "icloud.com", "me.com", "mac.com" -> Icloud
                "yahoo.com", "yahoo.fr", "ymail.com" -> Yahoo
                "fastmail.com", "fastmail.fm" -> Fastmail
                "outlook.com", "outlook.fr", "hotmail.com", "hotmail.fr", "live.com", "live.fr", "msn.com" -> Outlook
                "proton.me", "protonmail.com", "pm.me" -> Proton
                "orange.fr", "wanadoo.fr", "free.fr", "sfr.fr", "neuf.fr", "laposte.net" -> Isp
                else -> Unknown
            }
        }
    }
}

private fun n(v: Int) = v.toString().reversed().chunked(3).joinToString(" ").reversed()

/** Why an add or a check failed, in words, with the server's detail under it. */
/** "3.2", "21", "3,25": up to two decimals, as iOS writes the estimate's euros (`.fractionLength(0...2)`). */
private fun upToCents(v: Double, language: String): String {
    val fixed = eu.chezmaurice.core.text.printfFixed(v, 2).trimEnd('0').trimEnd('.')
    return if (language == "en") fixed else fixed.replace('.', ',')
}

private fun failure(e: Throwable, s: Strings, refusedKey: String, unreachableKey: String): Pair<String, String> {
    val api = e as? ApiError ?: return s("mail.failed") to (e.message ?: "")
    val key = if (api.status != 422) "mail.failed" else if (api.message.contains("refused the login")) refusedKey else unreachableKey
    return s(key) to api.message
}

@Composable
private fun ProviderHelp(help: String, page: Pair<String, String>?, possible: Boolean) {
    val theme = LocalMauriceTheme.current
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.surfaceAlt).border(0.5.dp, theme.rule, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(L(help), fontSize = 12.5.sp, color = if (possible) theme.inkSoft else WARM)
        page?.let { (key, url) -> Text(L(key) + " ↗", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = legible(LocalAccent.current, theme.isDark), modifier = Modifier.clickable { uri.openUri(url) }) }
    }
}

@Composable
internal fun Problem(error: String?, detail: String?, added: String?) {
    added?.let { StatusLine(it, ok = true) }
    error?.let {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(it, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = BAD)
            detail?.let { d -> SelectionContainer { Text(d, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = LocalMauriceTheme.current.inkMute) } }
        }
    }
}

@Composable
internal fun PrimaryButton(label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current, contentColor = Color.White)) {
        if (busy) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = Color.White); Spacer(Modifier.width(8.dp)) }
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Mail ───────────────────────────────────────────────────

/**
 * The member's own mailboxes (`MailPane`): adding one (with the help each
 * provider needs), the walk over their headers, the member's word on Maurice
 * reading them, and each mailbox's page.
 */
@Composable
fun MailPane(session: Session) {
    val theme = LocalMauriceTheme.current
    val scope = rememberCoroutineScope()
    val s = strings()
    val api = remember { session.api()?.let(::SettingsApi) } ?: return
    var accounts by remember { mutableStateOf<List<MailAccount>?>(null) }
    var scan by remember { mutableStateOf<MailScan?>(null) }
    var managing by remember { mutableStateOf<MailAccount?>(null) }
    var scanBusy by remember { mutableStateOf(false) }
    var readingBusy by remember { mutableStateOf(false) }
    var readingError by remember { mutableStateOf<String?>(null) }

    suspend fun loadScan() { api.mailScan()?.let { scan = it } }
    LaunchedEffect(Unit) { accounts = api.mailAccounts(); loadScan() }
    // While the walk runs, its progress every five seconds.
    LaunchedEffect(scan?.running == true) {
        while (scan?.running == true) { delay(5_000); loadScan() }
    }

    SetCaption(L("mail.intro"))
    val list = accounts.orEmpty()
    if (list.isNotEmpty()) {
        SetGroup(L("mail.yours")) {
            for (a in list) SetCard {
                Row(Modifier.fillMaxWidth().clickable { managing = a }.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    AccentGlyph("envelope")
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(a.address, fontSize = 13.5.sp, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        MailState(a.state)
                        box(scan, a)?.let { Text(boxSummary(it, scan, s), fontSize = 11.5.sp, color = theme.inkMute, maxLines = 2) }
                    }
                    Text(L("mail.manage"), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = theme.inkSoft)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = theme.inkSoft, modifier = Modifier.size(16.dp))
                }
            }
        }
        SetGroup(L("mail.scan.title")) {
            SetCard {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        AccentGlyph(if (scan?.state == "running") "tray.full" else "tray.full")
                        Spacer(Modifier.width(11.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(scanLine(scan, s), fontSize = 13.5.sp, color = theme.ink)
                            Text(L("mail.scope.all", list.size), fontSize = 11.5.sp, color = theme.inkMute)
                            if (scan?.state == "running") Text(L("mail.scan.running.hint"), fontSize = 11.5.sp, color = theme.inkMute)
                            else (scan?.lastError ?: scan?.error)?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = theme.inkMute, maxLines = 3) }
                        }
                    }
                    if (scan?.state == "running") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = theme.inkMute)
                    else Button(
                        onClick = { scanBusy = true; scope.launch { tryOr({}) { scan = api.startScan() }; scanBusy = false } },
                        enabled = !scanBusy && scan != null,
                        colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current, contentColor = Color.White),
                    ) {
                        Text(L(when (scan?.state) { "paused", "failed" -> "mail.scan.resume"; "done" -> "mail.scan.refresh"; else -> "mail.scan.start" }), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        SetCaption(L("mail.scan.caption"))
        SetGroup(L("mail.reading.title")) {
            SetCard {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val approved = readingApproved(scan)
                    val mayUseAI = eu.chezmaurice.maurice.consent.rememberMayUseAI()
                    Row(verticalAlignment = Alignment.Top) {
                        AccentGlyph("book.closed")
                        Spacer(Modifier.width(11.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(readingLine(scan, s), fontSize = 13.5.sp, color = theme.ink)
                            Text(L("mail.scope.reading", list.size), fontSize = 11.5.sp, color = theme.inkMute)
                            // Who reads it: the household's AI provider, named; the reading waits on a yes to it.
                            eu.chezmaurice.maurice.consent.SentToLine("mail.reading.by_local", "mail.reading.by", needsYes = !approved)
                            scan?.documents?.let { DocumentsProgress(it) }
                            readingError?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = theme.inkMute, maxLines = 3) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                readingBusy = true; readingError = null
                                scope.launch {
                                    tryOr({ readingError = it.message }) { scan = api.decideReading(if (approved) "decline" else "approve") }
                                    readingBusy = false
                                }
                            },
                            // A yes to the reading needs a yes to the provider first; taking it back never does.
                            enabled = !readingBusy && scan != null && scan?.reading?.state !in setOf("running", "paused") &&
                                (approved || mayUseAI),
                            colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current, contentColor = Color.White),
                        ) { Text(L(if (approved) "mail.reading.withdraw" else "mail.reading.approve"), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                        if (readingBusy) { Spacer(Modifier.width(8.dp)); CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
                    }
                }
            }
        }
        SetCaption(L("mail.reading.caption"))
    }
    SetGroup(L("mail.add")) { AddMailbox(api) { row -> accounts = accounts.orEmpty() + row; scope.launch { loadScan() } } }
    SetCaption(L("mail.privacy"))

    managing?.let { account ->
        MailboxPage(api, account, scan, appLanguage(session), onScan = { scan = it }, onChanged = { row -> accounts = accounts?.map { if (it.id == row.id) row else it } },
            onGone = { accounts = accounts?.filterNot { it.id == account.id }; managing = null; scope.launch { loadScan() } },
            refresh = { loadScan() }) { managing = null }
    }
}

@Composable
internal fun AccentGlyph(symbol: String) {
    val accent = LocalAccent.current
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(DomainGlyphs.of(symbol), null, tint = legible(accent, LocalMauriceTheme.current.isDark), modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun MailState(state: String, okKey: String = "mail.state.ok", errorKey: String = "mail.state.error", count: Int? = null) {
    when (state) {
        "ok" -> Text("✓ " + (if (count != null) L(okKey, count) else L(okKey)), fontSize = 11.5.sp, color = GOOD)
        "error" -> Text("⚠ " + (if (count != null) L(errorKey, count) else L(errorKey)), fontSize = 11.5.sp, color = WARM)
        else -> Text(L("mail.state.unchecked"), fontSize = 11.5.sp, color = LocalMauriceTheme.current.inkMute)
    }
}

private fun box(scan: MailScan?, a: MailAccount): Mailbox? = scan?.mailboxes?.firstOrNull { it.address.equals(a.address, ignoreCase = true) }

private fun boxSummary(box: Mailbox, scan: MailScan?, s: Strings): String {
    val r = box.reading
    var line = when {
        r.toRead + r.toLight > 0 -> s("mail.box.summary.reading", n(box.messages), n(r.read), n(r.toRead + r.toLight))
        r.read > 0 -> s("mail.box.summary.done", n(box.messages), n(r.read))
        else -> s("mail.box.summary.none", n(box.messages))
    }
    if (box.approved == false && box.estimate != null) line += " · " + s("mail.box.summary.waiting")
    else if (scan?.running == true && r.toRead + r.toLight > 0) line += " · " + s("mail.box.summary.running")
    return line
}

private fun scanLine(scan: MailScan?, s: Strings): String = when (scan?.state) {
    null -> s("mail.scan.loading")
    "running" -> s("mail.scan.running", n(scan.messages))
    "paused" -> s("mail.scan.paused", n(scan.messages))
    "done" -> s("mail.scan.done", n(scan.messages))
    "failed" -> s("mail.scan.failed", n(scan.messages))
    else -> s("mail.scan.idle")
}

private fun readingApproved(scan: MailScan?) = scan?.reading?.state?.let { it != "declined" } ?: false

private fun readingLine(scan: MailScan?, s: Strings): String {
    scan ?: return s("mail.reading.loading")
    val r = scan.reading ?: return s("mail.reading.none")
    return when (r.state) {
        "approved" -> s("mail.reading.approved", formatServerDate(r.decidedAt)?.substringBefore(' ') ?: r.decidedAt.orEmpty().take(10))
        "declined" -> s("mail.reading.declined")
        "running", "paused" -> s("mail.reading.running")
        "done" -> s("mail.reading.done")
        else -> s("mail.reading.pending")
    }
}

@Composable
private fun DocumentsProgress(d: MailDocuments) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (d.stage) {
            "preparing" -> { Text(L("mail.docs.preparing"), fontSize = 12.sp, color = theme.inkSoft); LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent) }
            "finishing" -> { Text(L("mail.docs.finishing"), fontSize = 12.sp, color = theme.inkSoft); LinearProgressIndicator({ 1f }, Modifier.fillMaxWidth(), color = accent) }
            else -> {
                Text(L("mail.docs.progress", n(d.done), n(d.total)), fontSize = 12.sp, color = theme.inkSoft)
                LinearProgressIndicator({ d.done.toFloat() / maxOf(d.total, 1) }, Modifier.fillMaxWidth(), color = accent)
                if (d.written > 0) Text(L("mail.docs.written", n(d.written)), fontSize = 11.sp, color = theme.inkMute)
            }
        }
    }
}

/** Adding a mailbox: the address first, then what its provider needs. */
@Composable
private fun AddMailbox(api: SettingsApi, onAdded: (MailAccount) -> Unit) {
    val scope = rememberCoroutineScope()
    val s = strings()
    var address by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var workspace by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var login by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf<String?>(null) }
    val detected = MailProvider.of(address.trim())
    // An unknown domain may be a Google Workspace one.
    val provider = if (detected == MailProvider.Unknown && workspace) MailProvider.Gmail else detected
    val canConnect = provider != null && provider.possible && !busy && password.isNotBlank() && (provider != MailProvider.Unknown || host.isNotBlank())

    fun add() {
        val p = provider ?: return
        busy = true; error = null; detail = null; added = null
        scope.launch {
            val row = tryOr({ e -> password = ""; failure(e, s, "mail.refused", "mail.unreachable").let { (a, b) -> error = a; detail = b } }) {
                api.addMail(
                    address.trim(), password,
                    provider = if (p == MailProvider.Gmail && workspace) "gmail" else null,
                    host = if (p == MailProvider.Unknown) host.trim().ifEmpty { null } else null,
                    port = if (p == MailProvider.Unknown) port.trim().toIntOrNull() else null,
                    username = if (p == MailProvider.Unknown) login.trim().ifEmpty { null } else null,
                )
            }
            if (row != null) {
                onAdded(row); added = s("mail.added")
                address = ""; password = ""; host = ""; port = ""; login = ""; workspace = false
            }
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Field(address, L("mail.address"), email = true) { address = it }
        if (provider != null) {
            ProviderHelp(provider.help, provider.page, provider.possible)
            if (detected == MailProvider.Unknown) SetCard { SetCheckRow(L("mail.workspace"), workspace) { workspace = !workspace } }
            if (provider == MailProvider.Unknown) {
                Field(host, L("mail.server")) { host = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(port, L("mail.port"), modifier = Modifier.width(100.dp)) { port = it }
                    Field(login, L("mail.login"), modifier = Modifier.weight(1f)) { login = it }
                }
            }
            if (provider.possible) {
                Field(password, L(if (provider.wantsAppPassword) "mail.password" else "mail.password.plain"), secret = true) { password = it }
                PrimaryButton(L(if (busy) "mail.checking" else "mail.connect"), busy, canConnect, ::add)
            }
        }
        Problem(error, detail, added)
    }
}

/** A mailbox's page (`manageSheet`): its state, what was read, sender rules, checking, a new password, removing, forgetting. */
@Composable
private fun MailboxPage(
    api: SettingsApi, account: MailAccount, scan: MailScan?, language: String, onScan: (MailScan) -> Unit, onChanged: (MailAccount) -> Unit,
    onGone: () -> Unit, refresh: suspend () -> Unit, onDismiss: () -> Unit,
) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    val s = strings()
    var current by remember { mutableStateOf(account) }
    var busy by remember { mutableStateOf(false) }
    var renewing by remember { mutableStateOf(false) }
    var newPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    var ruleBusy by remember { mutableStateOf<String?>(null) }
    var windowBusy by remember { mutableStateOf(false) }
    var readBusy by remember { mutableStateOf(false) }
    fun fail(e: Throwable) = failure(e, s, "mail.refused", "mail.unreachable").let { (a, b) -> error = a; detail = b }
    LaunchedEffect(scan?.running == true) { while (scan?.running == true) { delay(5_000); refresh() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(theme.surface).safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(account.address, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(L("common.done"), color = theme.ink) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                MailState(current.state)
                if (current.state == "error") current.lastError?.let { SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = theme.inkMute) } }
                box(scan, current)?.let { b ->
                    Section(L("mail.detail.scan.title")) {
                        Text(L("mail.detail.scan", n(b.messages)))
                        if (b.untriaged > 0) Text(L("mail.detail.untriaged", n(b.untriaged)))
                    }
                    Section(L("mail.detail.reading.title")) {
                        val r = b.reading
                        val years = scan?.reading?.years ?: 3
                        val all = years >= 50
                        if (r.window == 0) Text(if (all) L("mail.detail.nothing.all") else L("mail.detail.nothing", years))
                        else {
                            Text(if (all) L("mail.detail.window.all", n(r.window)) else L("mail.detail.window", n(r.window), years))
                            if (r.kept + r.skipped > 0) Text(L("mail.detail.sorted", n(r.kept + r.skipped), n(r.kept), n(r.skipped)))
                            if (r.toLight > 0) Text(L("mail.detail.to_sort", n(r.toLight)))
                            Text(L("mail.detail.read", n(r.read), n(r.toRead)))
                            if (scan?.running == true && r.toRead + r.toLight > 0) Text(L("mail.box.running"), color = theme.inkMute)
                        }
                        // Further back: five years, ten, or everything.
                        if (readingApproved(scan) && b.approved != false && !all) {
                            if (windowBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Box {
                                var open by remember { mutableStateOf(false) }
                                Text(L("mail.window.more") + " ▾", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = legible(accent, theme.isDark), modifier = Modifier.clickable { open = true }.padding(vertical = 4.dp))
                                DropdownMenu(open, { open = false }) {
                                    for (y in listOf(5, 10).filter { it > years }) DropdownMenuItem(text = { Text(L("mail.window.years", y)) }, onClick = {
                                        open = false; windowBusy = true; scope.launch { tryOr({}) { onScan(api.widenWindow(y)) }; windowBusy = false }
                                    })
                                    DropdownMenuItem(text = { Text(L("mail.window.all")) }, onClick = {
                                        open = false; windowBusy = true; scope.launch { tryOr({}) { onScan(api.widenWindow(50)) }; windowBusy = false }
                                    })
                                }
                            }
                        }
                        scan?.documents?.let { DocumentsProgress(it) }
                    }
                    // What is left, and the member's yes for this mailbox. The euros come for the operator only.
                    b.estimate?.let { e ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(WARM.copy(alpha = 0.10f)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val hours = if (e.hours < 1) L("mail.box.under_hour") else L("mail.box.hours", kotlin.math.round(e.hours).toInt())
                            var line = if (e.toSort > 0) L("mail.box.left", n(e.toSort), n(e.toRead), hours) else L("mail.box.left.read", n(e.toRead), hours)
                            e.euros?.let { line += " · " + L("mail.box.euros", upToCents(it, language)) }
                            Text(line, fontSize = 12.sp, color = theme.ink)
                            if (e.pending) Text(L("mail.box.pending"), fontSize = 11.sp, color = theme.inkMute)
                            if (b.approved == false) {
                                Text(L("mail.box.not_approved"), fontSize = 11.5.sp, color = theme.inkSoft)
                                ActionPill("checkmark", L("mail.box.read_this"), busy = readBusy) { readBusy = true; scope.launch { tryOr({}) { onScan(api.readThisBox(current.id)) }; readBusy = false } }
                            } else if (readingApproved(scan) && scan?.running != true) {
                                ActionPill("play", L("mail.box.read_now"), busy = readBusy) { readBusy = true; scope.launch { tryOr({}) { onScan(api.readNow()) }; readBusy = false } }
                            }
                        }
                    }
                    b.topSenders?.takeIf { it.isNotEmpty() }?.let { senders ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(L("mail.box.senders"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = theme.inkMute)
                            for (sender in senders) SenderRow(sender, busy = ruleBusy == sender.sender, enabled = ruleBusy == null) { days ->
                                ruleBusy = sender.sender
                                scope.launch { tryOr({}) { onScan(api.senderRule(sender.sender, days)) }; ruleBusy = null }
                            }
                        }
                    }
                }
                Section(L("mail.detail.actions")) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionPill("arrow.clockwise", L("mail.check"), busy = busy) {
                            busy = true; error = null
                            scope.launch { tryOr(::fail) { api.checkMail(current.id) }?.let { current = it; onChanged(it) }; busy = false }
                        }
                        ActionPill("key", L("mail.new_password"), enabled = !busy) { newPassword = ""; renewing = !renewing }
                        if (renewing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field(newPassword, L("mail.password"), secret = true, modifier = Modifier.weight(1f)) { newPassword = it }
                            TextButton(enabled = !busy && newPassword.isNotBlank(), onClick = {
                                busy = true; error = null
                                scope.launch {
                                    tryOr(::fail) { api.mailPassword(current.id, newPassword) }?.let { current = it; onChanged(it); renewing = false }
                                    newPassword = ""; busy = false
                                }
                            }) { Text(L("mail.save")) }
                        }
                        HorizontalDivider(color = theme.rule)
                        ActionPill("trash", L("mail.remove"), destructive = true, enabled = !busy) { removing = true }
                        ActionPill("trash", L("mail.forget"), destructive = true, enabled = !busy) { forgetting = true }
                    }
                }
                Problem(error, detail, null)
            }
        }
        if (removing) AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text(L("mail.remove")) }, text = { Text(L("mail.remove.confirm", current.address)) },
            confirmButton = { TextButton(onClick = { removing = false; scope.launch { tryOr(::fail) { api.removeMail(current.id) }?.let { onGone() } } }) { Text(L("mail.remove"), color = BAD) } },
            dismissButton = { TextButton(onClick = { removing = false }) { Text(L("common.cancel")) } },
            containerColor = theme.surface,
        )
        if (forgetting) AlertDialog(
            onDismissRequest = { forgetting = false },
            title = { Text(L("mail.forget.title")) }, text = { Text(L("mail.forget.message", current.address)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    for (history in listOf(false, true)) TextButton(onClick = {
                        forgetting = false; busy = true
                        scope.launch {
                            tryOr(::fail) { api.forgetMail(current.id, history) }?.let { onGone() }
                            busy = false
                        }
                    }) { Text(L(if (history) "mail.forget.history" else "mail.forget"), color = BAD) }
                }
            },
            dismissButton = { TextButton(onClick = { forgetting = false }) { Text(L("common.cancel")) } },
            containerColor = theme.surface,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val theme = LocalMauriceTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, color = theme.inkMute)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = theme.ink)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
        }
    }
}

/** A frequent sender, and how much of what they send Maurice keeps. */
@Composable
private fun SenderRow(sender: Sender, busy: Boolean, enabled: Boolean, onRule: (Int?) -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val options = listOf(null to "mail.sender.all", 7 to "mail.sender.week", 21 to "mail.sender.weeks3", 90 to "mail.sender.months3", 0 to "mail.sender.none")
    val label = options.firstOrNull { it.first == sender.ruleDays }?.let { L(it.second) } ?: L("mail.sender.days", sender.ruleDays ?: 0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(sender.sender, fontSize = 12.sp, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(L("mail.sender.count", n(sender.messages)), fontSize = 11.sp, color = theme.inkMute)
        }
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else Box {
            var open by remember { mutableStateOf(false) }
            Text(
                "$label ▾", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (sender.ruleDays == null) theme.inkSoft else legible(accent, theme.isDark),
                modifier = Modifier.clickable(enabled = enabled) { open = true }.padding(4.dp),
            )
            DropdownMenu(open, { open = false }) {
                for ((days, key) in options) DropdownMenuItem(
                    text = { Text(L(key)) },
                    trailingIcon = if (sender.ruleDays == days) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null,
                    onClick = { open = false; onRule(days) },
                )
            }
        }
    }
}

// ── Address books ──────────────────────────────────────────

private enum class ContactProvider(val help: String, val page: Pair<String, String>? = null) {
    Icloud("contacts.help.icloud", "mail.help.icloud.open" to "https://account.apple.com"),
    Fastmail("contacts.help.fastmail"), Mailfence("contacts.help.mailfence"), Google("contacts.help.google"), Other("contacts.help.other");

    val possible get() = this != Google
    val needsServer get() = this == Other

    companion object {
        fun of(username: String): ContactProvider = when (username.substringAfterLast('@', "").lowercase()) {
            "icloud.com", "me.com", "mac.com" -> Icloud
            "fastmail.com", "fastmail.fm" -> Fastmail
            "mailfence.com" -> Mailfence
            "gmail.com", "googlemail.com" -> Google
            else -> Other
        }
    }
}

/** The member's CardDAV address books (`ContactsPane`): add, sync, a new password, remove. */
@Composable
fun ContactsPane(session: Session) {
    val theme = LocalMauriceTheme.current
    val scope = rememberCoroutineScope()
    val s = strings()
    val api = remember { session.api()?.let(::SettingsApi) } ?: return
    var accounts by remember { mutableStateOf<List<ContactAccount>?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var otherServer by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf<String?>(null) }
    var renewing by remember { mutableStateOf<String?>(null) }
    var newPassword by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<ContactAccount?>(null) }
    LaunchedEffect(Unit) { accounts = api.contactAccounts() }
    val provider = ContactProvider.of(username.trim())
    val showsServer = provider.needsServer || otherServer
    val canConnect = (provider.possible || otherServer) && !busy && username.isNotBlank() && password.isNotBlank() && (!showsServer || server.isNotBlank())
    fun fail(e: Throwable) = failure(e, s, "contacts.refused", "contacts.unreachable").let { (a, b) -> error = a; detail = b }
    fun replace(row: ContactAccount) { accounts = accounts?.map { if (it.id == row.id) row else it } }

    SetCaption(L("contacts.intro"))
    accounts?.takeIf { it.isNotEmpty() }?.let { list ->
        SetGroup(L("contacts.yours")) {
            for (a in list) SetCard {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccentGlyph("person.crop.rectangle.stack")
                        Spacer(Modifier.width(11.dp))
                        Column {
                            Text(a.username, fontSize = 13.5.sp, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            MailState(a.state, "contacts.state.ok", "contacts.state.error", a.cards)
                        }
                    }
                    if (a.state == "error") a.lastError?.let { SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = theme.inkMute) } }
                    if (renewing == a.id) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(newPassword, L("mail.password"), secret = true, modifier = Modifier.weight(1f)) { newPassword = it }
                        TextButton(enabled = !busy && newPassword.isNotBlank(), onClick = {
                            busy = true; error = null
                            scope.launch { tryOr(::fail) { api.contactsPassword(a.id, newPassword) }?.let { replace(it); renewing = null }; newPassword = ""; busy = false }
                        }) { Text(L("mail.save")) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionPill("arrow.clockwise", L("contacts.sync"), busy = busy) { busy = true; scope.launch { tryOr(::fail) { api.syncContacts(a.id) }?.let(::replace); busy = false } }
                        ActionPill("key", L("mail.new_password"), enabled = !busy) { newPassword = ""; renewing = if (renewing == a.id) null else a.id }
                        Spacer(Modifier.weight(1f))
                        ActionPill("trash", L("mail.remove"), destructive = true, enabled = !busy) { removing = a }
                    }
                }
            }
        }
    }
    SetGroup(L("contacts.add")) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(username, L("contacts.username"), email = true) { username = it }
            if (username.isNotBlank()) {
                ProviderHelp(provider.help, provider.page, provider.possible)
                if (!provider.needsServer) SetCard { SetCheckRow(L("contacts.other_server"), otherServer) { otherServer = !otherServer } }
                if (showsServer) Field(server, L("contacts.server")) { server = it }
                if (provider.possible || otherServer) {
                    Field(password, L(if (provider == ContactProvider.Icloud || provider == ContactProvider.Fastmail) "mail.password" else "mail.password.plain"), secret = true) { password = it }
                    PrimaryButton(L(if (busy) "contacts.checking" else "mail.connect"), busy, canConnect) {
                        busy = true; error = null; detail = null; added = null
                        scope.launch {
                            val row = tryOr({ password = ""; fail(it) }) { api.addContacts(username.trim(), password, if (showsServer) server.trim().ifEmpty { null } else null) }
                            if (row != null) {
                                accounts = accounts.orEmpty() + row; added = s("contacts.added", row.cards)
                                username = ""; password = ""; server = ""; otherServer = false
                            }
                            busy = false
                        }
                    }
                }
            }
            Problem(error, detail, added)
        }
    }
    SetCaption(L("contacts.privacy"))
    removing?.let { a ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(L("mail.remove")) }, text = { Text(L("contacts.remove.confirm", a.username)) },
            confirmButton = { TextButton(onClick = { removing = null; scope.launch { tryOr(::fail) { api.removeContacts(a.id) }?.let { accounts = accounts?.filterNot { it.id == a.id } } } }) { Text(L("mail.remove"), color = BAD) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(L("common.cancel")) } },
            containerColor = theme.surface,
        )
    }
}
