package eu.chezmaurice.maurice.composer

import eu.chezmaurice.ui.accessibilityLabel
import eu.chezmaurice.ui.accessibilityName
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.composer.BookChapter
import eu.chezmaurice.core.composer.CONTEXT_BUDGET
import eu.chezmaurice.core.composer.ComposerStore
import eu.chezmaurice.core.composer.FileNode
import eu.chezmaurice.core.composer.FolderResolve
import eu.chezmaurice.core.composer.ItemType
import eu.chezmaurice.core.composer.NoteNode
import eu.chezmaurice.core.composer.NoteResolve
import eu.chezmaurice.core.composer.TrayItem
import eu.chezmaurice.maurice.chat.legible
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.maurice.domains.dashedBorder
import eu.chezmaurice.maurice.domains.fmtTok
import eu.chezmaurice.maurice.domains.strings
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.MauriceTheme
import eu.chezmaurice.ui.Strings

// What Maurice reads with each turn of a conversation (`ComposerViews.swift`).

private val CAUTION = Color(0xFFB97A1E)
private val WARN = Color(0xFFA6452E)

/** The colour of a weight: muted when light, ink, amber when heavy, red past the budget. */
fun tierColor(total: Int, theme: MauriceTheme): Color {
    val f = total.toDouble() / CONTEXT_BUDGET
    return when {
        f >= 1 -> legible(WARN, theme.isDark)
        f >= 0.55 -> legible(CAUTION, theme.isDark)
        f >= 0.18 -> theme.inkSoft
        else -> theme.inkMute
    }
}

fun typeSymbol(type: ItemType, moc: Boolean, kind: String = ""): String = when (type) {
    ItemType.Note -> if (moc) "rectangle.3.group" else "note.text"
    ItemType.Book -> "book"
    ItemType.Fiche -> "square.text.square"
    ItemType.Conversation -> "bubble.left.and.bubble.right"
    ItemType.Folder -> "folder"
    ItemType.File -> when (kind) { "img" -> "photo"; "pdf" -> "doc.richtext"; "text" -> "doc.text"; else -> "doc" }
}

@Composable
fun Glyph(symbol: String, tint: Color, size: Dp) = Icon(DomainGlyphs.of(symbol), null, tint = tint, modifier = Modifier.size(size))

@Composable
private fun Mono(text: String, size: TextUnit = 9.5.sp, color: Color = LocalMauriceTheme.current.inkMute, modifier: Modifier = Modifier) =
    Text(text, fontFamily = FontFamily.Monospace, fontSize = size, color = color, modifier = modifier)

private fun bookScopeLabel(item: TrayItem, s: Strings): String {
    if (item.scopeMode == "progress") {
        return if (item.progressChapter < 0) s("book.scope.progressUnstarted")
        else s("book.scope.progressAt", item.progressChapter + 1, maxOf(item.visibleChapters, item.progressChapter + 1))
    }
    val rep = s(if (item.representation == "full") "book.representation.fullText" else "book.representation.summaries")
    val scope = when (item.scopeMode) {
        "up_to" -> s("book.scope.upToChapter", item.uptoChapter + 1)
        "chapters" -> if (item.selectedRefs.size == 1) s("book.scope.oneChapter") else s("book.scope.nChapters", item.selectedRefs.size)
        else -> s("book.scope.allChapters")
    }
    return "$scope · $rep"
}

/** The mono line under a card's title. */
private fun cardMeta(item: TrayItem, s: Strings): String {
    val count = item.count ?: 1
    return when (item.type) {
        ItemType.Note -> when {
            !(item.moc || item.sub.contains("fans out")) -> s("note.meta.singleNote")
            item.recurse -> s("note.meta.withDescendants", count)
            else -> s("note.meta.descendantsOff")
        }
        ItemType.Book -> (if (item.sub.isEmpty()) "" else item.sub + " · ") + bookScopeLabel(item, s)
        ItemType.Conversation -> {
            val messages = s("conversation.meta.messages", count)
            when {
                item.conversationFull -> "$messages · ${s("conversation.meta.fullText")}"
                item.summaryStatus == "ready" -> "$messages · ${s("conversation.meta.summary")}"
                item.summaryStatus == "stale" -> "$messages · ${s("conversation.meta.summaryStale", item.uncovered)}"
                item.summaryStatus == "pending" -> "$messages · ${s("conversation.meta.summarising")}"
                else -> item.sub.ifEmpty { messages }
            }
        }
        ItemType.File -> item.sub.ifEmpty { item.kind }
        ItemType.Folder -> if (!item.recurse) s("folder.meta.directOnly") else s("folder.meta.files", count)
        ItemType.Fiche -> when {
            !item.includeFragments -> s("fiche.meta.aloneNoFragments")
            item.fragmentCount == 0 -> s("fiche.meta.noFragments")
            else -> s("fiche.meta.withFragments", item.fragmentCount)
        }
    }
}

/**
 * The tray, in review mode: what is loaded, the running total against the
 * budget, the domain's own bundle (read-only), a card per item that opens on
 * its options, and the way to add more.
 */
@Composable
fun ContextTray(store: ComposerStore, onDone: (() -> Unit)?, onAddMore: () -> Unit, modifier: Modifier = Modifier, maxHeight: Dp = 360.dp) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val state by store.state.collectAsState()
    var openId by remember { mutableStateOf<String?>(null) }
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(theme.surface).border(0.5.dp, theme.rule, RoundedCornerShape(18.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Mono(L("tray.contextCount", state.items.size).uppercase(), letterSpacing())
            Spacer(Modifier.width(10.dp))
            Mono(L("weight.ctxReadout", fmtTok(state.displayTotal)), 10.sp, tierColor(state.displayTotal, theme))
            if (state.attachmentCount > 0) {
                Spacer(Modifier.width(10.dp))
                Glyph("paperclip", theme.inkMute, 10.dp)
                Mono(L("file.attachmentsNoEstimate", state.attachmentCount))
            }
            Spacer(Modifier.weight(1f))
            if (onDone != null) TextButton(onClick = onDone) { Text(L("common.done"), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = legible(accent, theme.isDark)) }
        }
        // The domain's baked-in context: loaded, not removable here.
        if (state.lockedCount > 0) Row(Modifier.padding(horizontal = 14.dp).padding(bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph("lock.fill", theme.inkMute, 9.dp)
            Spacer(Modifier.width(6.dp))
            Mono(L("tray.bakedInto", state.lockedCount, state.lockedName, fmtTok(state.lockedWeight)))
        }
        if (state.items.isEmpty()) {
            Mono(L("tray.noContextYet"), 11.sp, modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp))
        } else {
            Column(
                Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (openId != null) 4.dp else 6.dp),
            ) {
                for (item in state.items) TrayCard(item, store, openId, onOpen = { openId = it })
            }
        }
        Row(
            Modifier.padding(horizontal = 10.dp).padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .dashedBorder(theme.ruleHard, 10.dp).clickable(onClick = onAddMore).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph("plus", theme.inkSoft, 14.dp)
            Spacer(Modifier.width(7.dp))
            Text(L("context.addContext"), fontSize = 12.5.sp, color = theme.inkSoft)
        }
    }
}

private fun letterSpacing() = 9.5.sp

/** One item: a header (opening on its options), the ✕; condensed while another is open. */
@Composable
private fun TrayCard(item: TrayItem, store: ComposerStore, openId: String?, onOpen: (String?) -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val strings = strings()
    val isOpen = openId == item.id
    val condensed = openId != null && !isOpen
    // Single files are leaves; so is a short conversation.
    val expandable = when (item.type) {
        ItemType.File -> false
        ItemType.Conversation -> item.summarisable || item.conversationFull
        else -> true
    }
    val shape = RoundedCornerShape(if (condensed) 7.dp else 9.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(theme.bg).border(0.5.dp, if (condensed) theme.rule else theme.ruleHard, shape)) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = expandable) { onOpen(if (isOpen) null else item.id) }
                .then(if (expandable) Modifier.accessibilityName(item.title) else Modifier)
                .padding(start = 11.dp, end = 4.dp, top = if (condensed) 0.dp else 5.dp, bottom = if (condensed) 0.dp else 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (expandable) Icon(
                if (isOpen) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = theme.inkMute, modifier = Modifier.size(if (condensed) 12.dp else 14.dp),
            ) else Spacer(Modifier.width(14.dp))
            Spacer(Modifier.width(7.dp))
            Glyph(typeSymbol(item.type, item.moc, item.kind), legible(accent, theme.isDark), if (condensed) 12.dp else 16.dp)
            Spacer(Modifier.width(9.dp))
            if (condensed) {
                Text(item.title, fontSize = 11.sp, color = theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (item.encrypted) { Spacer(Modifier.width(5.dp)); Glyph("lock.fill", theme.inkMute, 10.dp) }
                    if (item.heavy) { Spacer(Modifier.width(5.dp)); Glyph("exclamationmark.triangle.fill", CAUTION, 10.dp) }
                }
                Mono(cardMeta(item, strings), modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(6.dp))
            // Binaries have no token estimate: "n/a", not "~0".
            if (item.type == ItemType.File && item.na) Mono(L("file.noEstimate"), 10.sp)
            else {
                if (item.type == ItemType.Folder && item.binaryCount > 0) Glyph("paperclip", theme.inkMute, 10.dp)
                Mono("~" + fmtTok(item.weight), 10.sp, tierColor(item.weight, theme))
            }
            Box(Modifier.size(if (condensed) 26.dp else 32.dp).clip(CircleShape).clickable { store.remove(item.id) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, null, tint = theme.inkMute, modifier = Modifier.size(if (condensed) 11.dp else 13.dp))
            }
        }
        if (isOpen) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 0.dp).heightIn(min = 0.5.dp).background(theme.rule).size(0.dp, 0.5.dp))
            Column(Modifier.padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 12.dp)) {
                when (item.type) {
                    ItemType.Note -> NoteBody(item, store)
                    ItemType.Book -> BookBody(item, store)
                    ItemType.Folder -> FolderBody(item, store)
                    ItemType.Fiche -> FicheBody(item, store)
                    ItemType.Conversation -> ConversationBody(item, store)
                    ItemType.File -> {}
                }
            }
        }
    }
}

/** Two to four options in an inset track; the chosen one filled with the accent. */
@Composable
fun Segmented(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    Row(Modifier.clip(RoundedCornerShape(7.dp)).background(theme.surfaceAlt).border(0.5.dp, theme.rule, RoundedCornerShape(7.dp)).padding(2.dp)) {
        for ((v, label) in options) {
            val on = v == value
            Text(
                label, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = if (on) Color.White else theme.inkSoft, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(if (on) accent else Color.Transparent)
                    .clickable(role = Role.Button) { onChange(v) }.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun SwitchRow(checked: Boolean, title: String, caption: String?, tint: Color = LocalAccent.current, onToggle: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked, { onToggle() }, colors = SwitchDefaults.colors(checkedTrackColor = tint), modifier = Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = theme.ink)
            if (caption != null) Mono(caption)
        }
    }
}

@Composable
private fun QuietNote(text: String) {
    val theme = LocalMauriceTheme.current
    Mono(
        text, 10.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.bg).border(0.5.dp, theme.rule, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

// ── Conversation: summary or the whole transcript ─────────

@Composable
private fun ConversationBody(item: TrayItem, store: ComposerStore) {
    val theme = LocalMauriceTheme.current
    val hint = when {
        item.conversationFull -> L("conversation.hint.fullText", fmtTok(item.fullWeight))
        item.summaryStatus == "ready" -> L("conversation.hint.summary", fmtTok(item.fullWeight))
        item.summaryStatus == "stale" -> L("conversation.hint.summaryStale", item.uncovered)
        item.summaryStatus == "pending" -> L("conversation.hint.summarising", fmtTok(item.fullWeight))
        else -> ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L("conversation.representation"), fontSize = 12.5.sp, color = theme.ink, modifier = Modifier.weight(1f))
            Segmented(
                listOf("summary" to L("conversation.representation.summary"), "full" to L("conversation.representation.full")),
                if (item.conversationFull) "full" else "summary",
            ) { store.update(item.copy(conversationFull = it == "full")) }
        }
        if (hint.isNotEmpty()) Mono(hint)
    }
}

// ── Fiche: with its fragments or alone ─────────────────────

@Composable
private fun FicheBody(item: TrayItem, store: ComposerStore) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchRow(item.includeFragments, L("fiche.includeFragments"), null) { store.update(item.copy(includeFragments = !item.includeFragments)) }
        Mono(if (item.fragmentCount == 0) L("fiche.noFragmentsYet") else L("fiche.fragmentsHint", item.fragmentCount))
    }
}

// ── Note: its descendants, with a tree to leave some out ───

@Composable
private fun NoteBody(item: TrayItem, store: ComposerStore) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    var resolved by remember { mutableStateOf<NoteResolve?>(null) }
    LaunchedEffect(item.recurse, item.includeArchived, item.exclude) { resolved = store.resolveNote(item) }
    val r = resolved?.resolved
    val hasChildren = r?.hasChildren ?: (item.sub.contains("fans out") || item.moc)
    val count = r?.count ?: (item.count ?: 1)
    fun toggle(id: String) = store.update(item.copy(exclude = if (id in item.exclude) item.exclude - id else item.exclude + id))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!hasChildren) return@Column QuietNote(L("note.quiet.leaf"))
        val caption = if (item.recurse) L("note.pullsInNotes", count) + (if ((r?.manualExcluded ?: 0) > 0) L("note.deselectedSuffix", r!!.manualExcluded) else "") else L("note.justThisOne")
        SwitchRow(item.recurse, L("note.includeDescendants"), caption) { store.update(item.copy(recurse = !item.recurse)) }
        if (count >= 40 && item.recurse) Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph("exclamationmark.triangle.fill", CAUTION, 10.dp); Spacer(Modifier.width(4.dp)); Mono(L("note.heavy"), color = CAUTION)
        }
        val withheld = r?.archivedWithheld ?: 0
        if (item.recurse && (withheld > 0 || item.includeArchived)) {
            SwitchRow(item.includeArchived, if (item.includeArchived) L("note.includingArchived", withheld) else L("note.includeArchived", withheld), null, tint = CAUTION) {
                store.update(item.copy(includeArchived = !item.includeArchived))
            }
        }
        val tree = resolved?.tree
        if (item.recurse && tree != null) {
            TreeHeader(r?.manualExcluded ?: 0) { store.update(item.copy(exclude = emptyList())) }
            val expanded = remember(tree.id) { mutableStateOf(seedOpen(tree)) }
            TreeBox {
                fun walk(n: NoteNode, depth: Int) {
                    lines += { NoteRow(n, depth, item.includeArchived, expanded.value, onExpand = { id -> expanded.value = expanded.value.toggled(id) }, onToggle = ::toggle) }
                    if (n.id in expanded.value) n.children.forEach { walk(it, depth + 1) }
                }
                walk(tree, 0)
            }
        } else if (!item.recurse) QuietNote(L("note.quiet.descendantsOff"))
    }
}

private fun seedOpen(root: NoteNode): Set<String> = buildSet {
    add(root.id); for (c in root.children) { add(c.id); c.children.forEach { add(it.id) } }
}

private fun Set<String>.toggled(id: String) = if (id in this) this - id else this + id

@Composable
private fun TreeHeader(excluded: Int, onReset: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Mono(L("note.tree.instructions"), 8.5.sp, modifier = Modifier.weight(1f))
        if (excluded > 0) Mono(L("note.tree.reset", excluded), color = legible(LocalAccent.current, theme.isDark), modifier = Modifier.clickable(onClick = onReset))
    }
}

/** A scrolling outline, its rows gathered by a builder. */
private class TreeLines { val lines = mutableListOf<@Composable () -> Unit>() }

@Composable
private fun TreeBox(build: TreeLines.() -> Unit) {
    val theme = LocalMauriceTheme.current
    val lines = TreeLines().apply(build).lines
    Column(
        Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(8.dp)).background(theme.bg).border(0.5.dp, theme.rule, RoundedCornerShape(8.dp))
            .verticalScroll(rememberScrollState()).padding(4.dp),
    ) { lines.forEach { it() } }
}

@Composable
private fun NoteRow(node: NoteNode, depth: Int, includeArchived: Boolean, expanded: Set<String>, onExpand: (String) -> Unit, onToggle: (String) -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val isRoot = depth == 0
    val gated = node.archived && !includeArchived
    Row(Modifier.padding(start = (depth * 15).dp).padding(vertical = 3.dp, horizontal = 6.dp).alpha(if (node.included) 1f else 0.5f), verticalAlignment = Alignment.CenterVertically) {
        NodeCheck(if (isRoot) Check.Anchor else if (node.included) Check.On else Check.Off, disabled = isRoot || gated) { onToggle(node.id) }
        Spacer(Modifier.width(6.dp))
        Chevron(node.childCount > 0, node.id in expanded) { onExpand(node.id) }
        Spacer(Modifier.width(4.dp))
        Glyph(if (node.moc) "rectangle.3.group" else "note.text", if (node.moc) accent else theme.inkSoft, 12.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            node.title, fontSize = 12.sp, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textDecoration = if (node.included) null else TextDecoration.LineThrough, modifier = Modifier.weight(1f, fill = false),
        )
        if (node.moc) { Spacer(Modifier.width(4.dp)); Mono(L("note.tree.moc"), 8.sp, legible(accent, theme.isDark)) }
        Spacer(Modifier.weight(1f))
        if (node.archived) Mono(L(if (includeArchived) "note.tree.archived" else "note.tree.excludedBadge"), 8.5.sp, CAUTION)
        if (node.encrypted) Glyph("lock.fill", theme.inkMute, 9.dp)
    }
}

private enum class Check { On, Off, Anchor }

@Composable
private fun NodeCheck(state: Check, disabled: Boolean, onToggle: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val on = state != Check.Off
    Box(
        Modifier.size(16.dp).alpha(if (disabled) (if (state == Check.Anchor) 0.7f else 0.4f) else 1f).clip(RoundedCornerShape(4.dp))
            .background(if (on) accent else Color.Transparent).border(1.dp, if (on) accent else theme.ruleHard, RoundedCornerShape(4.dp))
            .clickable(enabled = !disabled, onClick = onToggle).accessibilityLabel(L(if (on) "note.tree.included" else "note.tree.excluded")),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            Check.Anchor -> Box(Modifier.size(5.dp).clip(CircleShape).background(Color.White))
            Check.On -> Text("✓", fontSize = 10.sp, color = Color.White)
            Check.Off -> {}
        }
    }
}

@Composable
private fun Chevron(hasChildren: Boolean, open: Boolean, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    if (!hasChildren) return Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(3.dp).clip(CircleShape).background(theme.inkMute.copy(alpha = 0.5f))) }
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = theme.inkMute,
        modifier = Modifier.size(14.dp).rotate(if (open) 90f else 0f).clip(CircleShape).clickable(onClick = onClick),
    )
}

// ── Folder: its contents, with a tree to leave files out ──

@Composable
private fun FolderBody(item: TrayItem, store: ComposerStore) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    var resolved by remember { mutableStateOf<FolderResolve?>(null) }
    LaunchedEffect(item.recurse, item.exclude) { resolved = store.resolveFolder(item) }
    val r = resolved
    val count = r?.count ?: (item.count ?: 0)
    val binaries = r?.binaryCount ?: item.binaryCount
    fun toggle(id: String) = store.update(item.copy(exclude = if (id in item.exclude) item.exclude - id else item.exclude + id))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (r != null && !r.hasChildren) return@Column QuietNote(L("folder.empty"))
        val caption = if (item.recurse) L("folder.pullsInFiles", count) + (if ((r?.manualExcluded ?: 0) > 0) L("note.deselectedSuffix", r!!.manualExcluded) else "") else L("folder.directOnly")
        SwitchRow(item.recurse, L("folder.includeContents"), caption) { store.update(item.copy(recurse = !item.recurse)) }
        if (binaries > 0) Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph("paperclip", theme.inkMute, 10.dp); Spacer(Modifier.width(5.dp)); Mono(L("file.attachmentsNoEstimate", binaries))
        }
        val tree = r?.tree
        if (tree != null) {
            TreeHeader(r.manualExcluded) { store.update(item.copy(exclude = emptyList())) }
            val expanded = remember(tree.id) { mutableStateOf(seedFolders(tree)) }
            TreeBox {
                fun walk(n: FileNode, depth: Int) {
                    lines += { FileRow(n, depth, expanded.value, onExpand = { id -> expanded.value = expanded.value.toggled(id) }, onToggle = ::toggle) }
                    if (n.id in expanded.value) n.children.forEach { walk(it, depth + 1) }
                }
                walk(tree, 0)
            }
        }
    }
}

private fun seedFolders(root: FileNode): Set<String> = buildSet {
    add(root.id)
    for (c in root.children) if (c.isFolder) { add(c.id); c.children.filter { it.isFolder }.forEach { add(it.id) } }
}

@Composable
private fun FileRow(node: FileNode, depth: Int, expanded: Set<String>, onExpand: (String) -> Unit, onToggle: (String) -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val structural = depth == 0 || node.isFolder
    Row(Modifier.padding(start = (depth * 15).dp).padding(vertical = 3.dp, horizontal = 6.dp).alpha(if (node.included) 1f else 0.5f), verticalAlignment = Alignment.CenterVertically) {
        // Only files carry a checkbox; folders stay structural.
        NodeCheck(if (structural) Check.Anchor else if (node.included) Check.On else Check.Off, disabled = structural) { onToggle(node.id) }
        Spacer(Modifier.width(6.dp))
        Chevron(node.childCount > 0, node.id in expanded) { onExpand(node.id) }
        Spacer(Modifier.width(4.dp))
        Glyph(typeSymbol(if (node.isFolder) ItemType.Folder else ItemType.File, false, node.kind), if (node.isFolder) accent else theme.inkSoft, 12.dp)
        Spacer(Modifier.width(6.dp))
        Text(node.name, fontSize = 12.sp, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = if (node.included) null else TextDecoration.LineThrough, modifier = Modifier.weight(1f))
        if (node.na) Mono(L("file.attachmentBadge"), 8.sp)
        else if (!node.isFolder) Mono("~" + fmtTok(node.weight), 9.sp)
    }
}

// ── Book: summaries or full text, which chapters, or the reader's position ──

@Composable
private fun BookBody(item: TrayItem, store: ComposerStore) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    var chapters by remember { mutableStateOf<List<BookChapter>>(emptyList()) }
    var showAll by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(item.rawId) { chapters = store.bookChapters(item.rawId) }
    val visible = chapters.filterNot { it.hidden }
    val hiddenCount = chapters.size - visible.size
    val inScope: Set<String> = when (item.scopeMode) {
        "progress" -> if (item.progressChapter < 0) emptySet() else visible.take(minOf(item.progressChapter + 1, visible.size)).map { it.ref }.toSet()
        "up_to" -> visible.take(item.uptoChapter.coerceIn(0, maxOf(0, visible.size - 1)) + 1).map { it.ref }.toSet()
        "chapters" -> item.selectedRefs.toSet()
        else -> visible.map { it.ref }.toSet()
    }
    fun setScope(scope: String) {
        var c = item.copy(scopeMode = scope)
        if (scope == "up_to" && c.uptoChapter == 0) c = c.copy(uptoChapter = minOf(6, maxOf(0, visible.size - 1)))
        if (scope == "progress") c = c.copy(representation = "summary")
        if (scope == "chapters" && c.selectedRefs.isEmpty()) c = c.copy(selectedRefs = inScope.toList())
        store.update(c)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // A followed book is summaries by definition: the control goes rather than lie.
        if (item.scopeMode != "progress") LabelledRow(L("book.representation")) {
            Segmented(listOf("summary" to L("book.representation.summary"), "full" to L("book.representation.full")), item.representation) {
                store.update(item.copy(representation = it))
            }
        }
        Mono(L("book.scope").uppercase(), 9.sp)
        Segmented(
            listOf("all" to L("book.scope.all"), "up_to" to L("book.scope.upTo"), "chapters" to L("book.scope.select"), "progress" to L("book.scope.progress")),
            item.scopeMode, ::setScope,
        )
        if (item.scopeMode == "progress") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(if (item.progressPaused) "pause.circle" else "bookmark", legible(accent, theme.isDark), 12.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (item.progressChapter < 0) L("book.progress.unstarted") else L("book.progress.at", item.progressChapter + 1, maxOf(visible.size, item.progressChapter + 1)),
                    fontSize = 11.5.sp, color = theme.ink,
                )
            }
            Mono(L(if (item.progressPaused) "book.progress.pausedHint" else "book.progress.hint"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallPill(if (item.progressPaused) "play" else "pause", L(if (item.progressPaused) "book.progress.resume" else "book.progress.pause"), on = item.progressPaused) {
                    store.setReadingPaused(item.rawId, !item.progressPaused)
                }
                SmallPill("arrow.counterclockwise", L("book.progress.reset"), on = false, enabled = item.progressChapter >= 0 || item.progressPaused) { confirmReset = true }
            }
        }
        if (item.scopeMode == "up_to" && visible.isNotEmpty()) {
            val last = item.uptoChapter.coerceIn(0, visible.size - 1)
            Row {
                Text(L("book.upTo"), fontSize = 11.5.sp, color = theme.inkSoft, modifier = Modifier.weight(1f))
                Mono(L("book.chapterNameLabel", last + 1, visible[last].name), 10.5.sp, legible(accent, theme.isDark))
            }
            Slider(
                value = last.toFloat(), onValueChange = { v -> if (v.toInt() != last) store.update(item.copy(uptoChapter = v.toInt())) },
                valueRange = 0f..maxOf(1, visible.size - 1).toFloat(), steps = maxOf(0, visible.size - 2),
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
            )
            Mono(L("book.spoilerSafe"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono(L(if (item.scopeMode == "chapters") "book.chooseSections" else "book.sectionsInScope"), 9.sp, modifier = Modifier.weight(1f))
            if (hiddenCount > 0) Mono(
                if (showAll) L("book.hideBackMatter") else L("book.showAllSections", hiddenCount), color = theme.inkSoft,
                modifier = Modifier.clickable { showAll = !showAll },
            )
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(8.dp)).background(theme.bg).border(0.5.dp, theme.rule, RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState()).padding(4.dp),
        ) {
            for (ch in if (showAll) chapters else visible) ChapterRow(ch, item.scopeMode, ch.ref in inScope, ch.ref in item.selectedRefs) {
                if (item.scopeMode == "chapters") store.update(item.copy(selectedRefs = if (ch.ref in item.selectedRefs) item.selectedRefs - ch.ref else item.selectedRefs + ch.ref))
            }
        }
        if (!showAll && hiddenCount > 0) Mono(L("book.backMatterHidden", hiddenCount), 9.sp)
    }
    // Losing one's place in a long book to a stray tap is not something an undo gives back.
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text(L("book.progress.resetConfirm")) },
        confirmButton = { TextButton(onClick = { confirmReset = false; store.resetReadingProgress(item.rawId) }) { Text(L("book.progress.reset"), color = WARN) } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(L("common.cancel")) } },
        containerColor = theme.surface,
    )
}

@Composable
private fun LabelledRow(label: String, control: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Mono(label.uppercase(), 9.sp, modifier = Modifier.weight(1f))
        control()
    }
}

@Composable
private fun SmallPill(symbol: String, title: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val color = if (on) legible(accent, theme.isDark) else theme.inkSoft
    Row(
        Modifier.alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(7.dp)).border(0.5.dp, if (on) accent.copy(alpha = 0.55f) else theme.ruleHard, RoundedCornerShape(7.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(symbol, color, 11.dp); Spacer(Modifier.width(5.dp)); Text(title, fontSize = 11.sp, color = color)
    }
}

@Composable
private fun ChapterRow(ch: BookChapter, mode: String, inScope: Boolean, selected: Boolean, onToggle: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val s = strings()
    Row(
        Modifier.fillMaxWidth().alpha(if (ch.hidden) 0.6f else 1f).clip(RoundedCornerShape(6.dp))
            .background(if (inScope && !ch.hidden) accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = mode == "chapters", onClick = onToggle).accessibilityName(ch.name, button = mode == "chapters").padding(vertical = 5.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mode == "chapters") NodeCheck(if (selected) Check.On else Check.Off, disabled = false, onToggle)
        else Box(Modifier.size(6.dp).clip(CircleShape).background(if (inScope) accent else theme.ruleHard))
        Spacer(Modifier.width(8.dp))
        Text(ch.name, fontSize = 12.sp, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (ch.hidden) {
            val m = s(when (ch.sectionType) { "front_matter" -> "book.matter.front"; "back_matter" -> "book.matter.back"; else -> "book.matter.section" })
            Mono(if (ch.wordCount > 0) s("book.matter.words", m, ch.wordCount) else m, 8.5.sp, CAUTION)
        }
    }
}

/** The tray of the conversation on screen; null in previews without a server. */
val LocalComposer = androidx.compose.runtime.staticCompositionLocalOf<ComposerStore?> { null }

/** Makes a tray bound to no conversation — a domain editor's baked-in context. */
val LocalComposerFactory = androidx.compose.runtime.staticCompositionLocalOf<(() -> ComposerStore)?> { null }
