package eu.chezmaurice.maurice.settings

import eu.chezmaurice.core.net.shownMessage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.model.formatServerDate
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.settings.EraseAnswer
import eu.chezmaurice.core.settings.GitAccess
import eu.chezmaurice.core.settings.ImportHistory
import eu.chezmaurice.core.settings.Library
import eu.chezmaurice.core.settings.LibraryFile
import eu.chezmaurice.core.settings.LibraryFolder
import eu.chezmaurice.core.settings.SettingsApi
import eu.chezmaurice.maurice.consent.AiDataList
import eu.chezmaurice.maurice.consent.AiProviderList
import eu.chezmaurice.maurice.consent.LocalAiConsent
import eu.chezmaurice.core.settings.Upload
import eu.chezmaurice.maurice.chat.legible
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.maurice.domains.dashedBorder
import eu.chezmaurice.maurice.domains.strings
import eu.chezmaurice.maurice.platform.plainTextClip
import eu.chezmaurice.maurice.platform.rememberFilePicker
import eu.chezmaurice.maurice.platform.rememberFileSaver
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val GOOD = Color(0xFF3D8B4F)
internal val BAD = Color(0xFFC0392B)

/** A status under an action: plain, good (green) or bad (red). */
@Composable
internal fun StatusLine(text: String, ok: Boolean = false, bad: Boolean = false) =
    Text(text, fontSize = 11.5.sp, color = if (bad) BAD else if (ok) GOOD else LocalMauriceTheme.current.inkSoft)

/** An outlined action in the accent (or red when it destroys). */
@Composable
internal fun ActionPill(symbol: String, label: String, busy: Boolean = false, destructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val tint = if (destructive) BAD.copy(alpha = 0.85f) else legible(LocalAccent.current, theme.isDark)
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).border(0.5.dp, tint.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled && !busy, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) { CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = tint); Spacer(Modifier.width(6.dp)) }
        Icon(DomainGlyphs.of(symbol), null, tint = tint.copy(alpha = if (enabled) 1f else 0.5f), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = tint.copy(alpha = if (enabled) 1f else 0.5f))
    }
}

/** A titled card with its explanation and its actions (`MyDataView.card`). */
@Composable
internal fun InfoCard(title: String, text: String?, content: @Composable () -> Unit) {
    val theme = LocalMauriceTheme.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(theme.surfaceAlt).border(0.5.dp, theme.rule, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = theme.ink)
        if (text != null) Text(text, fontSize = 11.5.sp, color = theme.inkSoft)
        content()
    }
}

/** A plain field; [secret] hides it, [email] asks the keyboard for an address. */
@Composable
internal fun Field(value: String, label: String, secret: Boolean = false, email: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) =
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else if (email) KeyboardType.Email else KeyboardType.Uri, autoCorrectEnabled = false),
    )

internal suspend fun <T> tryOr(onError: (Throwable) -> Unit, block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    onError(e); null
}

// ── Files ──────────────────────────────────────────────────

/**
 * The member's file library (`FilesLibraryView`): folders and files as a
 * tree, adding files from the device, new folders, renaming, deleting.
 */
@Composable
fun FilesPane(session: Session) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    val api = remember { session.api()?.let(::SettingsApi) } ?: return
    var library by remember { mutableStateOf<Library?>(null) }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<Pair<String?, Boolean>?>(null) } // (id or null for new, isFolder)
    var name by remember { mutableStateOf("") }
    suspend fun load() { api.library()?.let { library = it } }
    LaunchedEffect(Unit) { load() }
    val pick = rememberFilePicker(multiple = true, mimeTypes = emptyList()) { files ->
        uploading = true
        scope.launch {
            for (f in files) tryOr({}) { api.upload(f) }
            load(); uploading = false
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionPill("arrow.up.doc", L("settings.files.addfiles"), busy = uploading, onClick = pick)
        ActionPill("folder", L("settings.files.newfolder"), enabled = !uploading) { name = ""; naming = null to true }
        Spacer(Modifier.weight(1f))
        library?.summary?.takeIf { it.count > 0 }?.let { s ->
            Text("${s.count} · ${formatBytes(s.sizeBytes)} / ${formatBytes(s.quotaBytes)}", fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, color = theme.inkMute, maxLines = 1)
        }
    }
    val lib = library
    if (lib == null || (lib.folders.isEmpty() && lib.files.isEmpty())) {
        // The empty library is one big place to tap.
        Column(
            Modifier.fillMaxWidth().heightIn(min = 260.dp).clip(RoundedCornerShape(12.dp)).dashedBorder(theme.ruleHard, 12.dp).clickable(onClick = pick).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(theme.surfaceAlt), contentAlignment = Alignment.Center) {
                Icon(DomainGlyphs.of("arrow.up.doc"), null, tint = theme.inkSoft, modifier = Modifier.size(20.dp))
            }
            Text(L("settings.files.empty.title"), fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = theme.ink)
            Text(L("settings.files.empty.tap").replace("**", ""), fontSize = 11.5.sp, color = theme.inkSoft, textAlign = TextAlign.Center)
            Text(L("settings.files.empty.hint"), fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, color = theme.inkMute, textAlign = TextAlign.Center)
        }
    } else {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(theme.surfaceAlt).border(0.5.dp, theme.rule, RoundedCornerShape(12.dp)).padding(5.dp)) {
            fun folders(parent: String?) = lib.folders.filter { it.parentId == parent }.sortedBy { it.name.lowercase() }
            fun files(folder: String?) = lib.files.filter { it.folderId == folder }.sortedBy { it.name.lowercase() }
            @Composable fun walk(parent: String?, depth: Int) {
                for (f in folders(parent)) {
                    FolderRow(f, depth, folders(f.id).size + files(f.id).size, open = f.id !in collapsed,
                        onToggle = { collapsed = if (f.id in collapsed) collapsed - f.id else collapsed + f.id },
                        onRename = { name = f.name; naming = f.id to true },
                        onDelete = { scope.launch { tryOr({}) { api.deleteFolder(f.id) }; load() } })
                    if (f.id !in collapsed) walk(f.id, depth + 1)
                }
                for (file in files(parent)) FileRow(file, depth, selected == file.id,
                    onSelect = { selected = if (selected == file.id) null else file.id },
                    onRename = { name = file.name; naming = file.id to false },
                    onDelete = { scope.launch { tryOr({}) { api.deleteFile(file.id) }; selected = null; load() } })
            }
            walk(null, 0)
        }
        SetCaption(L("settings.files.caption"))
    }

    naming?.let { (id, isFolder) ->
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(L(if (id == null) "settings.files.newfolder" else "settings.files.rename")) },
            text = { Field(name, L("settings.files.folder_name")) { name = it } },
            confirmButton = {
                TextButton(onClick = {
                    val n = name.trim(); naming = null
                    if (n.isNotEmpty()) scope.launch {
                        tryOr({}) { if (id == null) api.newFolder(n) else if (isFolder) api.renameFolder(id, n) else api.renameFile(id, n) }
                        load()
                    }
                }) { Text(L(if (id == null) "common.create" else "common.save")) }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text(L("common.cancel")) } },
            containerColor = theme.surface,
        )
    }
}

@Composable
private fun FolderRow(f: LibraryFolder, depth: Int, count: Int, open: Boolean, onToggle: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val theme = LocalMauriceTheme.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 34.dp).clip(RoundedCornerShape(7.dp))
                .combinedClickableCompat(onClick = onToggle, onLongClick = { menu = true })
                .padding(start = (4 + depth * 16).dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (open) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = theme.inkMute, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Icon(DomainGlyphs.of("folder"), null, tint = theme.inkSoft, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(7.dp))
            Text(f.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("$count", fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, color = theme.inkMute)
        }
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { Text(L("settings.files.rename")) }, onClick = { menu = false; onRename() })
            androidx.compose.material3.DropdownMenuItem(text = { Text(L("sidebar.delete"), color = BAD) }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun FileRow(file: LibraryFile, depth: Int, selected: Boolean, onSelect: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 34.dp).clip(RoundedCornerShape(7.dp)).background(if (selected) accent.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onSelect).padding(start = (24 + depth * 16).dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(DomainGlyphs.of("doc"), null, tint = theme.inkMute, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(7.dp))
        Text(file.name, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(6.dp))
        val ext = file.name.substringAfterLast('.', "").ifEmpty { file.kind }
        Text(ext, fontFamily = FontFamily.Monospace, fontSize = 8.5.sp, color = theme.inkMute, modifier = Modifier.border(0.5.dp, theme.rule, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(Icons.Default.Edit, L("settings.files.rename"), tint = theme.inkSoft, modifier = Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onRename).padding(7.dp))
            Icon(Icons.Default.Delete, L("sidebar.delete"), tint = BAD.copy(alpha = 0.85f), modifier = Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onDelete).padding(7.dp))
        } else Text(formatBytes(file.sizeBytes), fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, color = theme.inkMute)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit) =
    this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick))

// ── History import ─────────────────────────────────────────

/** Bring ChatGPT or Claude conversations (`ImportConversationsView`): upload the export, follow the job. */
@Composable
fun ImportPane(session: Session) {
    val theme = LocalMauriceTheme.current
    val api = remember { session.api()?.let(::SettingsApi) } ?: return
    Text(L("settings.import.caption"), fontSize = 12.5.sp, color = theme.inkSoft)
    for ((provider, label) in listOf("anthropic" to "Claude", "chatgpt" to "ChatGPT")) ImportCard(api, provider, label)
    Text(L("settings.import.night"), fontSize = 11.5.sp, color = theme.inkMute)
}

@Composable
private fun ImportCard(api: SettingsApi, provider: String, label: String) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    val strings = strings()
    var history by remember { mutableStateOf<ImportHistory?>(null) }
    var line by remember { mutableStateOf<Triple<String, Boolean, Boolean>?>(null) } // text, ok, bad
    var busy by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(provider) { history = api.importHistory(provider) }
    val pick = rememberFilePicker(multiple = false, mimeTypes = listOf("application/zip")) { files ->
        val file = files.firstOrNull() ?: return@rememberFilePicker
        if (!file.name.lowercase().endsWith(".zip")) { line = Triple(strings("settings.import.failed", strings("settings.import.not_zip")), false, true); return@rememberFilePicker }
        busy = true
        scope.launch {
            line = Triple(strings("settings.import.uploading"), false, false)
            val job = tryOr({ line = Triple(strings("settings.import.failed", it.shownMessage() ?: "?"), false, true) }) { api.startImport(provider, file) }
            if (job != null) {
                line = Triple(strings("settings.import.parsing"), false, false)
                for (i in 0 until 2000) {
                    delay(1_500)
                    val s = api.importStatus(job) ?: continue
                    if (s.status == "failed") { line = Triple(strings("settings.import.failed", s.error ?: "?"), false, true); break }
                    if (s.status == "done") {
                        val c = s.result?.conversations ?: 0
                        line = Triple(if (c == 0) strings("settings.import.done_none") else strings("settings.import.done", c, s.result?.messages ?: 0), true, false)
                        break
                    }
                    if (s.phase == "indexing") line = Triple(strings("settings.import.indexing", s.done ?: 0, s.total ?: 0), false, false)
                }
                history = api.importHistory(provider)
            }
            busy = false
        }
    }
    InfoCard(label, strings("settings.import.intro", label, strings("settings.import.source.$provider"))) {
        Text(
            history?.watermark?.let { strings("settings.import.synced_through", formatServerDate(it)?.substringBefore(' ') ?: it.take(10)) } ?: strings("settings.import.never"),
            fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = theme.inkMute,
        )
        for (run in history?.history.orEmpty().take(3)) Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(when (run.status) { "done" -> GOOD; "failed" -> BAD; else -> Color(0xFFB97A1E) }.copy(alpha = 0.7f)))
            Spacer(Modifier.width(8.dp))
            Text(strings("settings.import.run", run.conversations, run.messages), fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = theme.inkMute, modifier = Modifier.weight(1f))
            Text(formatServerDate(run.ranAt)?.substringBefore(' ') ?: run.ranAt.take(10), fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = theme.inkMute)
        }
        line?.let { (t, ok, bad) -> StatusLine(t, ok, bad) }
        // What happens to an import, the provider named, and no import without a yes to it.
        eu.chezmaurice.maurice.consent.SentToLine("settings.import.ai_local", "settings.import.ai")
        val consent = eu.chezmaurice.maurice.consent.LocalAiConsent.current
        val providers = consent?.state?.collectAsState()?.value?.providers.orEmpty()
        val mayUseAI = eu.chezmaurice.maurice.consent.rememberMayUseAI()
        ActionPill("tray.full", L(if (history?.watermark == null) "settings.import.choose" else "settings.import.choose_more"), busy = busy, enabled = mayUseAI) {
            if (providers.isEmpty()) pick() else confirming = true
        }
        if (confirming) androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = theme.surface,
            title = { Text(L("settings.import.confirm.title"), color = theme.ink) },
            text = {
                Text(L("settings.import.confirm.text", label,
                    eu.chezmaurice.maurice.consent.providerNames(providers, eu.chezmaurice.maurice.consent.LocalAppLanguage.current)), color = theme.inkSoft)
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirming = false; pick() }) { Text(L("settings.import.confirm.ok"), color = theme.ink) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirming = false }) { Text(L("ai.consent.ask.cancel"), color = theme.inkSoft) } },
        )
    }
}

// ── My data ────────────────────────────────────────────────

/**
 * What the server holds of the member, theirs to take, clone, bring back or
 * erase (`MyDataView`) — guests included: they have an account, and the
 * right to leave it.
 */
@Composable
fun MyDataPane(session: Session, member: Member, onClose: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    val strings = strings()
    val clipboard = LocalClipboard.current
    val api = remember { session.api()?.let(::SettingsApi) } ?: return
    var exportLine by remember { mutableStateOf<Triple<String, Boolean, Boolean>?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var importLine by remember { mutableStateOf<Triple<String, Boolean, Boolean>?>(null) }
    var importing by remember { mutableStateOf(false) }
    var git by remember { mutableStateOf<GitAccess?>(null) }
    var accountEvents by remember { mutableStateOf<List<eu.chezmaurice.core.settings.AccountEvent>?>(null) }
    val interventions = LocalInterventions.current
    LaunchedEffect(Unit) {
        val events = api.accountEvents()
        accountEvents = events
        // Shown, so read: the dot on the way here goes out.
        if (events.orEmpty().any { it.seenAt == null }) api.accountEventsSeen()
        interventions?.unread = 0
    }
    var gitLine by remember { mutableStateOf<String?>(null) }
    var gitBusy by remember { mutableStateOf(false) }
    var rotated by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }
    var erasing by remember { mutableStateOf<String?>(null) } // data | account
    var erased by remember { mutableStateOf<EraseAnswer?>(null) }

    val save = rememberFileSaver("application/gzip") { target ->
        if (target == null) { exporting = false; exportLine = null; return@rememberFileSaver }
        scope.launch {
            exportLine = Triple(strings("mydata.export.preparing"), false, false)
            val ok = tryOr({ exportLine = Triple(strings("mydata.failed", it.shownMessage() ?: "?"), false, true) }) { api.export { target.write(it) } }
            if (ok != null) exportLine = Triple(strings("mydata.export.done"), true, false)
            exporting = false
        }
    }
    val pick = rememberFilePicker(multiple = false, mimeTypes = listOf("application/gzip", "application/x-gzip", "application/octet-stream")) { files ->
        val file = files.firstOrNull() ?: return@rememberFilePicker
        importing = true
        scope.launch {
            importLine = Triple(strings("mydata.import.uploading"), false, false)
            val r = tryOr({ e -> importLine = Triple(if ((e as? ApiError)?.status == 409) strings("mydata.import.already") else strings("mydata.failed", e.message ?: "?"), false, true) }) { api.importMine(file) }
            if (r != null) importLine = Triple(strings("mydata.import.done", r.conversations, r.files, r.notes), true, false)
            importing = false
        }
    }
    fun loadGit(rotate: Boolean) {
        gitBusy = true
        scope.launch {
            val r = tryOr({ gitLine = strings("mydata.failed", it.shownMessage() ?: "?") }) { api.git(rotate) }
            when {
                r != null -> { git = r; rotated = rotate; copied = null; gitLine = null }
                gitLine == null -> gitLine = strings("mydata.clone.no_garden")
            }
            gitBusy = false
        }
    }

    Text(L("mydata.caption"), fontSize = 12.5.sp, color = theme.inkSoft)
    // What the publisher does with the data, one tap away where the member manages it.
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Text(L("mydata.privacy"), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = legible(LocalAccent.current, theme.isDark),
        modifier = Modifier.clickable { uri.openUri(eu.chezmaurice.maurice.consent.PRIVACY_URL) })

    // The member's answer about AI providers, theirs to give or take back here.
    LocalAiConsent.current?.let { AiProvidersCard(it, appLanguage(session)) }

    InfoCard(L("mydata.export.title"), L("mydata.export.text")) {
        ActionPill("square.and.arrow.up", L("mydata.export.button"), busy = exporting) { exporting = true; save("maurice-${member.username ?: "member"}.tar.gz") }
        exportLine?.let { (t, ok, bad) -> StatusLine(t, ok, bad) }
    }

    InfoCard(L("mydata.clone.title"), L("mydata.clone.text")) {
        val g = git
        if (g != null) {
            @Composable fun fieldRow(label: String, value: String, shown: String = value) = Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 11.5.sp, color = theme.inkSoft, modifier = Modifier.widthIn(min = 76.dp))
                SelectionContainer(Modifier.weight(1f)) { Text(shown, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text(
                    L(if (copied == value) "mydata.clone.copied" else "mydata.clone.copy"), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = legible(accent, theme.isDark),
                    modifier = Modifier.clickable { scope.launch { clipboard.setClipEntry(plainTextClip(value)); copied = value } }.padding(4.dp),
                )
            }
            fieldRow(L("mydata.clone.address"), g.url)
            fieldRow(L("mydata.clone.username"), g.username)
            fieldRow(L("mydata.clone.token"), g.token, g.token.take(9) + "…")
            if (rotated) StatusLine(L("mydata.clone.rotated"), ok = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(L("mydata.clone.conversations"), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = theme.ink)
                    Text(L("mydata.clone.conversations_note"), fontSize = 11.sp, color = theme.inkSoft)
                }
                Switch(g.exportConversations, { on ->
                    git = g.copy(exportConversations = on)
                    scope.launch {
                        val kept = tryOr({ gitLine = strings("mydata.failed", it.shownMessage() ?: "?") }) { api.setGitExport(on) }
                        git = g.copy(exportConversations = kept ?: g.exportConversations)
                    }
                }, colors = SwitchDefaults.colors(checkedTrackColor = accent))
            }
            Text(L("mydata.clone.howto"), fontSize = 11.5.sp, color = theme.inkSoft)
            Text(L("mydata.clone.warning"), fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = theme.ink)
            ActionPill("arrow.clockwise", L("mydata.clone.rotate"), busy = gitBusy) { loadGit(rotate = true) }
        } else ActionPill("arrow.down.doc", L("mydata.clone.button"), busy = gitBusy) { loadGit(rotate = false) }
        gitLine?.let { StatusLine(it) }
    }

    InfoCard(L("mydata.import.title"), L("mydata.import.text")) {
        ActionPill("tray.full", L("mydata.import.button"), busy = importing, onClick = pick)
        importLine?.let { (t, ok, bad) -> StatusLine(t, ok, bad) }
    }

    // The register of interventions: the date, what was done, the reason.
    // Read when shown; a register, not an alarm.
    InfoCard(L("account_events.title"), L("account_events.text")) {
        accountEvents?.let { events ->
            if (events.isEmpty()) Text(L("account_events.none"), fontSize = 12.5.sp, color = theme.inkSoft)
            for (e in events) Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                // Unread when the list opened: the dot stays for this visit.
                Box(Modifier.padding(top = 6.dp).size(7.dp).clip(CircleShape)
                    .background(if (e.seenAt == null) interventionOrange() else Color.Transparent))
                Text(accountEventSentence(e), fontSize = 12.5.sp, color = theme.ink)
            }
        }
    }

    InfoCard(L("mydata.erase.title"), L("mydata.erase.text")) {
        ActionPill("trash", L("mydata.erase.button"), destructive = true) { erasing = "data" }
        erased?.takeIf { it.scope == "data" }?.let { a ->
            StatusLine(L("mydata.erase.done"), ok = true)
            if (!a.corpus) StatusLine(L("mydata.residual.corpus"))
            for (remote in a.residual.gardenRemotes) StatusLine(L("mydata.residual.remote", remote))
            if (a.residual.publishedSite) StatusLine(L("mydata.residual.site"))
            if (a.residual.householdArchives > 0) StatusLine(L("mydata.residual.archives", a.residual.householdArchives))
        }
    }
    InfoCard(L("mydata.delete.title"), L("mydata.delete.text")) {
        ActionPill("person", L("mydata.delete.button"), destructive = true) { erasing = "account" }
    }
    Text(L("mydata.backups"), fontSize = 11.5.sp, color = theme.inkMute)

    erasing?.let { scopeName ->
        EraseDialog(api, scopeName, member, onDismiss = { erasing = null }) { answer ->
            erasing = null
            if (scopeName == "account") {
                onClose()
                scope.launch { session.state.value.current?.let { session.disconnectMember(it.id, member.id) } }
            } else erased = answer
        }
    }
}

/**
 * Erasing is confirmed by typing one's username, and the password or PIN.
 * The username comes from `/api/users/me`, which the picker may not have.
 */
@Composable
private fun EraseDialog(api: SettingsApi, scopeName: String, member: Member, onDismiss: () -> Unit, onDone: (EraseAnswer) -> Unit) {
    val theme = LocalMauriceTheme.current
    val strings = strings()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val username = member.username.orEmpty()
    val ready = !busy && username.isNotEmpty() && confirm.trim() == username
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 440.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(theme.surface).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(L(if (scopeName == "account") "mydata.delete.title" else "mydata.erase.title"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = theme.ink)
            Text(L(if (scopeName == "account") "mydata.confirm.account" else "mydata.confirm.data"), fontSize = 12.5.sp, color = theme.inkSoft)
            Text(L("mydata.confirm.type", username), fontSize = 11.5.sp, color = theme.inkSoft)
            Field(confirm, username) { confirm = it }
            Field(secret, L("mydata.confirm.secret"), secret = true) { secret = it }
            failure?.let { Text(it, fontSize = 11.5.sp, color = BAD) }
            Row {
                TextButton(onClick = onDismiss) { Text(L("mydata.confirm.cancel"), color = theme.inkSoft) }
                Spacer(Modifier.weight(1f))
                TextButton(enabled = ready, onClick = {
                    busy = true; failure = null
                    scope.launch {
                        val answer = tryOr({ e ->
                            val code = (e as? ApiError)?.message.orEmpty()
                            failure = if (strings.has("mydata.error.$code")) strings("mydata.error.$code") else code.ifEmpty { e.message ?: "?" }
                        }) { api.erase(scopeName, confirm.trim(), secret) }
                        busy = false
                        if (answer != null) onDone(answer)
                    }
                }) {
                    if (busy) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp); Spacer(Modifier.width(6.dp)) }
                    Text(L(if (scopeName == "account") "mydata.delete.button" else "mydata.erase.button"), color = if (ready) BAD else theme.inkMute)
                }
            }
        }
    }
}

/** Settings → My data → AI providers: who receives what, and the member's yes or no, revocable. */
@Composable
private fun AiProvidersCard(store: eu.chezmaurice.core.consent.AiConsentStore, language: String) {
    val consent by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(store) { store.refresh() }
    fun decide(accept: Boolean) {
        busy = true
        scope.launch { store.decide(accept); busy = false }
    }
    val record = consent.record
    val line = when {
        record == null -> L("mydata.ai.none")
        else -> L(if (record.status == "accepted") "mydata.ai.accepted" else "mydata.ai.declined",
            eu.chezmaurice.maurice.budget.longDate(record.decidedAt, language) ?: record.decidedAt)
    }
    InfoCard(L("mydata.ai.title"), L("mydata.ai.text")) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AiProviderList(consent.providers, highlighted = consent.newProviders.map { it.id }.toSet())
            AiDataList()
            StatusLine(line, ok = consent.accepted && !consent.needsAnswer)
            if (consent.accepted) {
                if (consent.newProviders.isNotEmpty()) ActionPill("checkmark", L("mydata.ai.accept"), busy = busy) { decide(true) }
                ActionPill("hand.raised", L("mydata.ai.withdraw"), busy = busy) { decide(false) }
                StatusLine(L("mydata.ai.withdraw_note"))
            } else {
                ActionPill("checkmark", L("mydata.ai.accept"), busy = busy) { decide(true) }
            }
        }
    }
}
