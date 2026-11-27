package eu.chezmaurice.maurice.composer

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.composer.ComposerStore
import eu.chezmaurice.core.composer.ItemType
import eu.chezmaurice.core.composer.SearchEntry
import eu.chezmaurice.core.model.ToolFamily
import eu.chezmaurice.maurice.chat.legible
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.maurice.domains.LocalDomains
import eu.chezmaurice.maurice.domains.dashedBorder
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.wordmark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How the picker orders what it found; recency first — one reaches for what one was just working on. */
private enum class Sort(val key: String) { Recent("sort.recent"), Relevance("sort.relevance"), Title("sort.title") }

/**
 * Search the member's notes, fiches, books, folders, files and conversations
 * and toggle them into the tray (`AddContextSheet`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContextSheet(store: ComposerStore, onDismiss: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val state by store.state.collectAsState()
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchEntry>>(emptyList()) }
    var sort by remember { mutableStateOf(Sort.Recent) }
    var uploading by remember { mutableStateOf(false) }
    val uploadScope = rememberCoroutineScope()
    val pickFile = eu.chezmaurice.maurice.platform.rememberFilePicker(multiple = false, mimeTypes = emptyList()) { files ->
        val file = files.firstOrNull() ?: return@rememberFilePicker
        uploading = true
        uploadScope.launch { store.addUploaded(file); uploading = false }
    }
    // One request per pause in typing; an answer to an older query never overwrites a newer one.
    LaunchedEffect(q) {
        delay(150)
        store.search(q)?.let { results = it }
    }
    val ordered = when (sort) {
        Sort.Relevance -> results
        Sort.Title -> results.sortedBy { it.title.lowercase() }
        Sort.Recent -> results.sortedWith(compareByDescending<SearchEntry> { it.updatedAt ?: "" }.thenBy { it.title.lowercase() })
    }
    val groups = listOf(
        "context.section.notes" to ItemType.Note, "context.section.fiches" to ItemType.Fiche, "context.section.books" to ItemType.Book,
        "context.section.folders" to ItemType.Folder, "context.section.files" to ItemType.File, "context.section.conversations" to ItemType.Conversation,
    )
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = theme.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(L("context.addContext"), style = wordmark(21.sp), color = theme.ink, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(L("common.done"), fontWeight = FontWeight.SemiBold, color = legible(accent, theme.isDark)) }
            }
            BasicTextField(
                q, { q = it }, singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = theme.ink), cursorBrush = SolidColor(theme.ink),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(theme.bg).border(0.5.dp, theme.rule, RoundedCornerShape(10.dp)).padding(10.dp),
                decorationBox = { inner ->
                    if (q.isEmpty()) Text(L("context.searchPlaceholder"), fontSize = 15.sp, color = theme.inkMute)
                    inner()
                },
            )
            // A document from the device: into the library, and into the tray.
            if (store.uploader != null) Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .dashedBorder(theme.ruleHard, 10.dp)
                    .clickable(enabled = !uploading, onClick = pickFile).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph("arrow.up.doc", theme.inkSoft, 15.dp)
                Spacer(Modifier.width(7.dp))
                Text(L(if (uploading) "context.uploading" else "context.uploadFile"), fontSize = 12.5.sp, color = theme.inkSoft)
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in Sort.entries) {
                    val on = s == sort
                    Text(
                        L(s.key), fontSize = 11.5.sp, color = if (on) legible(accent, theme.isDark) else theme.inkSoft,
                        modifier = Modifier.clip(RoundedCornerShape(50)).border(0.5.dp, if (on) accent else theme.ruleHard, RoundedCornerShape(50))
                            .clickable { sort = s }.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                for ((label, type) in groups) {
                    val rows = ordered.filter { it.type == type }
                    if (rows.isEmpty()) continue
                    item(key = "head-$label") {
                        Text(L(label).uppercase(), fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, letterSpacing = 0.7.sp, color = theme.inkMute, modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 4.dp))
                    }
                    items(rows, key = { it.id }) { e -> EntryRow(e, state.has(e.id)) { store.toggle(e) } }
                }
                if (results.isEmpty() && q.isNotEmpty()) item {
                    Text(L("context.nothingMatches", q), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = theme.inkMute, modifier = Modifier.padding(18.dp))
                }
            }
        }
    }
}

@Composable
private fun EntryRow(e: SearchEntry, on: Boolean, onToggle: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().background(if (on) accent.copy(alpha = 0.10f) else Color.Transparent).clickable(onClick = onToggle).padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(typeSymbol(e.type, e.moc, e.kind), if (on) accent else theme.inkSoft, 18.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.title, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (e.encrypted) { Spacer(Modifier.width(6.dp)); Glyph("lock.fill", theme.inkMute, 10.dp) }
            }
            if (e.sub.isNotEmpty()) Text(e.sub, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = theme.inkMute, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Glyph(if (on) "checkmark.circle.fill" else "plus.circle", if (on) accent else theme.inkMute, 20.dp)
    }
}

// ── Tools ──────────────────────────────────────────────────

/**
 * Which tool families a domain or a conversation may use (`ToolFamilyPicker`):
 * the always-on ones named, the garden's in a grid, the experimental ones folded.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolFamilyPicker(families: List<ToolFamily>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    val theme = LocalMauriceTheme.current
    var showExperimental by remember { mutableStateOf(false) }
    val alwaysOn = families.filter { it.alwaysOn }
    val garden = families.filter { it.group == "garden" }.sortedWith(compareBy<ToolFamily> { if (it.id == "garden-notes") 0 else 1 }.thenByDescending { it.count })
    val experimental = families.filter { it.group == "experimental" }.sortedByDescending { it.count }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (alwaysOn.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph("bolt.fill", legible(Color(0xFF3D6B4F), theme.isDark), 10.dp)
            Spacer(Modifier.width(6.dp))
            Text(L("tools.always_on", alwaysOn.joinToString(" · ") { it.title }), fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = theme.inkMute)
        }
        if (garden.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(L("tools.section.garden").uppercase(), fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 0.8.sp, color = theme.inkMute)
            FamilyGrid(garden, selected, onChange)
        }
        if (experimental.isNotEmpty()) {
            Text(
                (if (showExperimental) "▾ " else "▸ ") + L("tools.section.experimental", experimental.size),
                fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 0.8.sp, color = theme.inkMute,
                modifier = Modifier.clickable { showExperimental = !showExperimental },
            )
            if (showExperimental) FamilyGrid(experimental, selected, onChange)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FamilyGrid(items: List<ToolFamily>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
        for (f in items) {
            val on = f.id in selected
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (on) accent.copy(alpha = 0.10f) else theme.bg)
                    .border(if (on) 1.dp else 0.5.dp, if (on) accent.copy(alpha = 0.5f) else theme.rule, RoundedCornerShape(9.dp))
                    .clickable { onChange(if (on) selected - f.id else selected + f.id) }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(f.icon, if (on) accent else theme.inkMute, 15.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(L(if (f.count == 1) "tools.count.one" else "tools.count.other", f.count), fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = theme.inkMute)
                }
                if (on) Text("✓", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = legible(accent, theme.isDark))
            }
        }
    }
}

/** The tools of one conversation (`ConversationToolsSheet`): an override of what it inherits. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationToolsSheet(conversationId: String, store: ComposerStore, onDismiss: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val domains = LocalDomains.current
    val families = domains?.state?.collectAsState()?.value?.families.orEmpty()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(conversationId) { store.conversationFamilies(conversationId)?.let { selected = it.toSet() } }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = theme.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(L("common.cancel"), color = theme.inkSoft) }
                Text(L("chat.tools_title"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = theme.ink, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                TextButton(onClick = { scope.launch { store.setConversationFamilies(conversationId, selected.toList()); onDismiss() } }) {
                    Text(L("common.save"), fontWeight = FontWeight.SemiBold, color = legible(accent, theme.isDark))
                }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(L("chat.tools_hint"), fontSize = 12.sp, color = theme.inkSoft)
                ToolFamilyPicker(families, selected) { selected = it }
            }
        }
    }
}
