package eu.chezmaurice.maurice.domains

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.chat.ChatController
import eu.chezmaurice.core.domains.DomainStore
import eu.chezmaurice.core.model.Brief
import eu.chezmaurice.core.model.BriefRefresh
import eu.chezmaurice.core.model.Domain
import eu.chezmaurice.core.model.DomainConversations
import eu.chezmaurice.core.model.SeededNotes
import eu.chezmaurice.core.model.formatServerDate
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.wordmark
import kotlinx.coroutines.launch

/**
 * A domain's page (`DomainBriefSheet`): the brief Maurice keeps on it,
 * editable — the member's words are what he reads from the next turn on —
 * and erasable; when and by whom it was last written; "rewrite now"; "talk
 * about it"; the notes seeded in the garden; the conversations the night
 * filed there on its own, each of which can be taken back.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BriefSheet(domain: Domain, store: DomainStore, controller: ChatController, onOpened: () -> Unit, onDismiss: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val nav = LocalDomainsNav.current
    val strings = strings()
    val scope = rememberCoroutineScope()
    val chat by controller.state.collectAsState()

    var loaded by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf(true) }
    var brief by remember { mutableStateOf<Brief?>(null) }
    var notes by remember { mutableStateOf<SeededNotes?>(null) }
    var text by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmErase by remember { mutableStateOf(false) }
    var bound by remember { mutableStateOf<DomainConversations?>(null) }

    suspend fun loadBound() { bound = store.boundConversations(domain.id) }
    LaunchedEffect(domain.id) {
        val page = runCatching { store.brief(domain.id) }.getOrNull()
        found = page != null
        brief = page?.brief
        notes = page?.notes?.takeIf { it.total > 0 }
        text = brief?.text.orEmpty()
        loaded = true
        loadBound()
    }

    val trimmed = text.trim()
    val changed = trimmed != brief?.text.orEmpty()
    val busy = saving || refreshing

    fun save() = scope.launch {
        saving = true
        runCatching { store.saveBrief(domain.id, trimmed) }
            .onSuccess { kept ->
                brief = kept; text = kept?.text.orEmpty()
                notice = strings(if (kept == null) "brief.erased" else "brief.saved")
            }
            .onFailure { notice = strings("brief.save_failed") }
        saving = false
    }
    fun erase() = scope.launch {
        saving = true
        if (runCatching { store.deleteBrief(domain.id) }.getOrDefault(false)) {
            brief = null; text = ""; notice = strings("brief.erased")
        } else notice = strings("brief.save_failed")
        saving = false
    }
    fun rewrite() = scope.launch {
        refreshing = true
        notice = strings("brief.rewriting")
        val outcome = store.refreshBrief(domain.id)
        runCatching { store.brief(domain.id) }.getOrNull()?.brief?.let { brief = it; text = it.text }
        notice = when (outcome) {
            is BriefRefresh.Written -> strings(if (outcome.sources == 1) "brief.outcome.written_one" else "brief.outcome.written_other", outcome.sources)
            BriefRefresh.Unchanged -> strings("brief.outcome.unchanged")
            BriefRefresh.Capped -> strings("brief.outcome.capped")
            is BriefRefresh.Failed -> strings("brief.outcome.failed", outcome.why)
        }
        refreshing = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = theme.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(L("brief.title"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = theme.ink, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(L("common.done"), color = theme.ink) }
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DomainMark(domain, size = 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(L("brief.kicker"), fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 0.8.sp, color = theme.inkMute)
                        Text(domain.name, style = wordmark(22.sp), color = theme.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (domain.tagline.isNotBlank()) Text(domain.tagline, fontSize = 12.sp, color = theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                when {
                    !loaded -> Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = theme.inkMute)
                    }
                    !found -> Text(L("brief.unreachable"), fontSize = 13.sp, color = theme.inkSoft)
                    else -> {
                        val b = brief
                        if (b != null) {
                            val stamp = listOf(ageOf(b.updatedAt, strings), formatServerDate(b.updatedAt) ?: b.updatedAt).filter { it.isNotEmpty() }.joinToString(" · ")
                            val line = buildList {
                                add(strings(if (b.byMember) "brief.by_you" else "brief.by_maurice", stamp))
                                if (!b.byMember && b.sources.isNotEmpty()) add(strings(if (b.sources.size == 1) "brief.sources_one" else "brief.sources_other", b.sources.size))
                            }.joinToString(" · ")
                            MonoLine(if (b.byMember) "person" else "moon.stars", line)
                        } else Text(L("brief.none"), fontSize = 13.sp, color = theme.inkSoft)

                        OutlinedTextField(
                            text, { text = it }, enabled = !busy,
                            placeholder = { Text(L("brief.placeholder"), fontSize = 13.5.sp, color = theme.inkMute) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.5.sp, color = theme.ink),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = theme.bg, unfocusedContainerColor = theme.bg, disabledContainerColor = theme.bg,
                                focusedBorderColor = accent, unfocusedBorderColor = theme.rule,
                            ),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                        )
                        notice?.let { Text(it, fontSize = 12.sp, color = theme.inkSoft) }

                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Save: the member's words replace the brief. Empty = erase.
                            Text(
                                L("common.save"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                                modifier = Modifier.clip(CircleShape).background(if (changed && !busy) accent else theme.inkMute.copy(alpha = 0.4f))
                                    .clickable(enabled = changed && !busy) { save() }.padding(horizontal = 16.dp, vertical = 7.dp),
                            )
                            PillButton(if (refreshing) "hourglass" else "arrow.clockwise", L("brief.rewrite"), enabled = !busy) { rewrite() }
                            PillButton("bubble.left", L("brief.talk"), enabled = !busy) {
                                controller.startNew(domain.id)
                                onDismiss(); onOpened()
                            }
                            if (brief != null) PillButton("trash", L("brief.erase"), tint = ERASE, enabled = !busy) { confirmErase = true }
                            PillButton("pencil.and.outline", L("domain.edit"), enabled = !busy) { nav.openEditor(domain) }
                        }

                        notes?.let { n ->
                            val uri = LocalUriHandler.current
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val words = buildList {
                                    add(strings(if (n.total == 1) "brief.notes_one" else "brief.notes_other", n.total))
                                    if (n.unreviewed > 0) add(strings(if (n.unreviewed == 1) "brief.notes.unreviewed_one" else "brief.notes.unreviewed_other", n.unreviewed))
                                }.joinToString(" · ")
                                MonoLine("leaf", words)
                                n.webPath?.let { path ->
                                    Text(
                                        " · " + strings("brief.notes.open") + " ↗", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = theme.inkSoft,
                                        modifier = Modifier.clickable { uri.openUri(path) },
                                    )
                                }
                            }
                        }

                        bound?.let { b ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                MonoLine("bubble.left.and.bubble.right", buildList {
                                    add(strings(if (b.total == 1) "proposals.conversations.one" else "proposals.conversations.other", b.total))
                                    if (b.auto > 0) add(strings("brief.conversations.auto", b.auto))
                                }.joinToString(" · "))
                                // The conversation on screen, alone with Maurice: bind it here, or take it out.
                                val active = chat.active
                                if (active != null && chat.participants.size <= 1) {
                                    val here = active.mauriceId == domain.id
                                    PillButton(if (here) "link.badge.minus" else "link.badge.plus", L(if (here) "brief.conversations.detach_current" else "brief.conversations.attach_current")) {
                                        scope.launch { controller.setDomain(if (here) null else domain.id, active.id); loadBound() }
                                    }
                                }
                                val auto = b.conversations.filter { it.isAuto }
                                if (auto.isNotEmpty()) {
                                    Text(L("brief.conversations.auto.hint"), fontSize = 12.sp, color = theme.inkMute)
                                    for (row in auto) Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(0.5.dp, theme.ruleHard, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            row.title?.takeIf { it.isNotBlank() } ?: L("chat.new_conversation"), fontSize = 13.sp, color = theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f).clickable { controller.open(row.id); onDismiss(); onOpened() },
                                        )
                                        Text(
                                            L("pastille.detach"), fontSize = 11.5.sp, color = theme.inkMute,
                                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { scope.launch { controller.setDomain(null, row.id); loadBound() } }.padding(4.dp),
                                        )
                                    }
                                }
                            }
                        }
                        Text(L("brief.explainer"), fontSize = 12.sp, color = theme.inkMute, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }

    if (confirmErase) AlertDialog(
        onDismissRequest = { confirmErase = false },
        title = { Text(L("brief.erase.title")) },
        text = { Text(L("brief.erase.message")) },
        confirmButton = { TextButton(onClick = { confirmErase = false; erase() }) { Text(L("brief.erase"), color = ERASE) } },
        dismissButton = { TextButton(onClick = { confirmErase = false }) { Text(L("common.cancel")) } },
        containerColor = theme.surface,
    )
}

@Composable
private fun MonoLine(symbol: String, text: String) {
    val theme = LocalMauriceTheme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(DomainGlyphs.of(symbol), null, tint = theme.inkMute, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = theme.inkMute)
    }
}
