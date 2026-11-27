package eu.chezmaurice.carnet.entry

import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalStrings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.chezmaurice.carnet.platform.rememberMauriceApp
import eu.chezmaurice.carnet.ui.GridButton
import eu.chezmaurice.carnet.ui.GridLabel
import eu.chezmaurice.carnet.ui.GridRule
import eu.chezmaurice.carnet.ui.LocalApi
import eu.chezmaurice.carnet.ui.MemberBadge
import eu.chezmaurice.ui.accessibilityLabel
import eu.chezmaurice.ui.accessibilityName
import androidx.compose.ui.semantics.Role
import eu.chezmaurice.carnet.ui.inlineMarkdown
import eu.chezmaurice.carnet.ui.minePaper
import eu.chezmaurice.core.carnet.EntryConversation
import eu.chezmaurice.core.carnet.EntryPage
import eu.chezmaurice.core.carnet.GardenApi
import eu.chezmaurice.core.carnet.MineItem
import eu.chezmaurice.core.carnet.ShelfEntry
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.maurice.absoluteUrl
import eu.chezmaurice.maurice.chat.ReplyText
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.wordmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** `*([label](maurice://conversations/<id>))*` at the end of a note: the conversation it came from. */
private val PROVENANCE = Regex("""\s*\*?\(\[([^\]]*)\]\((maurice://conversations/[^)\s]+)\)\)\*?\s*$""")

/**
 * One thing written on the entry (`MineItemView`): its eyebrow (where it
 * comes from), the quote or highlight, the note; signed by its author when
 * the entry is shared, or by Maurice for what came of a conversation.
 */
@Composable
fun MineItemView(item: MineItem, signed: Boolean) {
    val theme = LocalMauriceTheme.current
    val api = LocalApi.current
    val maurice = rememberMauriceApp()
    val backlink = item.text?.let { t -> PROVENANCE.find(t)?.let { m -> Triple(t.substring(0, m.range.first), m.groupValues[1], m.groupValues[2]) } }
    val eyebrow = buildList {
        add(when (item.kind) {
            "highlight" -> listOfNotNull(item.place?.chapterTitle ?: L("carnet.item.highlight"), if (item.place?.view == "summary") L("carnet.item.summary") else null).joinToString(" · ")
            "quote" -> item.place?.page?.let { L("carnet.item.quote_page", it) } ?: L("carnet.item.quote")
            "resonance" -> item.from?.let { L("carnet.item.resonance_from", it.label) } ?: L("carnet.item.resonance")
            "fragment" -> L("carnet.item.fragment")
            else -> L("carnet.item.note")
        })
        item.date?.let { add(it) }
    }.joinToString(" · ")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (signed) Box(Modifier.padding(top = 1.dp)) {
            val who = item.conversation?.initiator ?: item.author
            when {
                item.conversation != null || item.kind == "fragment" -> Box(Modifier.accessibilityLabel(if (item.conversation != null) L("carnet.item.asked_by", who?.displayName ?: L("carnet.item.someone")) else "Maurice", button = false)) {
                    MauriceMark(24.dp)
                    if (item.conversation != null && who != null && api != null) Box(Modifier.offset(14.dp, 14.dp).border(1.5.dp, theme.bg, CircleShape)) {
                        MemberBadge(who.displayName, who.avatarColor, absoluteUrl(api.baseUrl, who.avatarUrl), 14.dp)
                    }
                }
                item.author != null && api != null -> Box(Modifier.accessibilityLabel(item.author!!.displayName, button = false)) { MemberBadge(item.author!!.displayName, item.author!!.avatarColor, absoluteUrl(api.baseUrl, item.author!!.avatarUrl), 24.dp) }
                else -> Spacer(Modifier.size(24.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val edited = item.edited?.takeIf { it.isNotEmpty() }
            if (edited == null) GridLabel(eyebrow, 9.5.sp)
            else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GridLabel(eyebrow, 9.5.sp)
                // Rewritten since: said after the date, quietly.
                Text(NoteEdits.editedLine(edited, LocalStrings.current), fontSize = 11.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = theme.inkMute, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (item.kind == "fragment") {
                item.summary?.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = theme.ink) }
                eu.chezmaurice.carnet.ui.MarkdownBlocks(item.text.orEmpty(), serif = false, maxLines = 6)
            } else {
                val quoted = !item.quote.isNullOrEmpty()
                if (quoted) Text(
                    item.quote!!, fontSize = 14.5.sp, color = theme.ink,
                    modifier = Modifier.clip(RoundedCornerShape(2.dp)).background(if (item.kind == "highlight") theme.inkWash else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 3.dp),
                )
                val text = backlink?.first ?: item.text
                if (!text.isNullOrEmpty()) Row(Modifier.height(IntrinsicSize.Min)) {
                    if (quoted) { Box(Modifier.width(1.5.dp).fillMaxHeight().background(theme.ruleHard)); Spacer(Modifier.width(9.dp)) }
                    Text(inlineMarkdown(text, theme.inkSoft), fontSize = 14.sp, color = if (quoted) theme.inkSoft else theme.ink)
                }
                backlink?.let { (_, label, url) ->
                    Row(
                        Modifier.padding(top = 2.dp).clickable(onClickLabel = L("carnet.item.open_conversation"), role = Role.Button) { url.substringAfterLast('/').takeIf { maurice.installed }?.let(maurice::open) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Icon(DomainGlyphs.of("bubble.left.and.bubble.right"), null, tint = theme.inkSoft, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = theme.inkSoft, textDecoration = TextDecoration.Underline)
                    }
                }
            }
        }
    }
}

/** The conversations held with Maurice about the entry, each with what came of it. */
@Composable
fun ConversationsSection(entry: ShelfEntry, conversations: List<EntryConversation>) {
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MauriceMark(16.dp)
            GridLabel(if (conversations.size == 1) L("carnet.conversations.one") else L("carnet.conversations.other", conversations.size), 9.5.sp)
        }
        for (c in conversations) ConversationLine(entry, c)
    }
}

@Composable
private fun ConversationLine(entry: ShelfEntry, conversation: EntryConversation) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val api = LocalApi.current ?: return
    val garden = remember(api) { GardenApi(api) }
    val maurice = rememberMauriceApp()
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf(conversation.summary) }
    var writing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    fun write(force: Boolean) {
        writing = true; failure = null
        scope.launch {
            try { summary = garden.conversationSummary(entry, conversation.id, force) } catch (e: CancellationException) { throw e } catch (e: Throwable) { failure = strings("carnet.conversation.summary_failed", e.message.orEmpty()) }
            writing = false
        }
    }
    val setting = listOf(
        whenOf(conversation.updatedAt),
        conversation.maurice?.let { L("carnet.conversation.with", it.name) } ?: if (conversation.via == "entry") L("carnet.conversation.from_entry") else L("carnet.conversation.in_maurice"),
        if (conversation.messageCount == 1) L("carnet.conversation.messages.one") else L("carnet.conversation.messages.other", conversation.messageCount),
    ).filter { it.isNotEmpty() }.joinToString(" · ")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(0.5.dp, theme.ruleHard, RoundedCornerShape(10.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(conversation.displayTitle, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = theme.ink, maxLines = 2)
                GridLabel(setting, 9.sp)
            }
            if (maurice.installed) Text("↗", fontSize = 15.sp, color = theme.inkSoft, modifier = Modifier.clip(CircleShape).clickable { maurice.open(conversation.id) }.accessibilityLabel(L("carnet.conversation.open_in_maurice")).padding(8.dp))
        }
        val shown = summary
        if (shown != null) {
            Box(Modifier.graphicsLayer { alpha = if (writing) 0.5f else 1f }) { ReplyText(shown.text, api.baseUrl) }
            if (shown.stale && !writing) Text(L("carnet.conversation.stale"), fontSize = 12.sp, color = theme.inkMute)
            if (!writing) Text("↻ " + L("carnet.rewrite"), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = theme.inkSoft, modifier = Modifier.clickable { write(true) }.accessibilityName(L("carnet.rewrite_summary")))
        } else if (!writing) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L("carnet.conversation.later"), fontSize = 12.5.sp, color = theme.inkMute, modifier = Modifier.weight(1f))
            Text(L("carnet.conversation.write_now"), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = theme.ink, textDecoration = TextDecoration.Underline, modifier = Modifier.clickable { write(false) })
        }
        if (writing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = theme.inkMute)
            Text(L("carnet.conversation.writing"), fontSize = 13.sp, color = theme.inkMute)
        }
        failure?.let { Text(it, fontSize = 12.5.sp, color = theme.inkSoft) }
    }
}

/**
 * A note on the entry (`NoteSheet`): the text, and a quote (with its page
 * when there is no book to tell it). Kept on the member's side. Opened on a
 * note already written ([editing]), it starts from it and Save replaces it;
 * [onDelete] when the member may take it off.
 */
@Composable
fun NoteSheet(
    title: String, offersPage: Boolean, chapterTitle: String? = null,
    editing: MineItem? = null, onDelete: (suspend () -> Unit)? = null,
    onDismiss: () -> Unit, onKeep: suspend (String, String?, String?) -> Unit,
) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(editing?.text.orEmpty()) }
    var quote by remember { mutableStateOf(editing?.quote.orEmpty()) }
    var page by remember { mutableStateOf(editing?.place?.page.orEmpty()) }
    var quoting by remember { mutableStateOf(quote.isNotEmpty() || page.isNotEmpty()) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(theme.surface).navigationBarsPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GridLabel(L("note.sheet.goes_on_your_side", chapterTitle ?: title), 9.5.sp, modifier = Modifier.weight(1f))
                    eu.chezmaurice.carnet.ui.DictationMic { words -> text = if (text.isBlank()) words else text.trimEnd() + " " + words }
                    Spacer(Modifier.width(8.dp))
                    Text("“", fontSize = 18.sp, color = if (quoting) theme.bg else theme.inkSoft,
                        modifier = Modifier.size(34.dp).clip(CircleShape).background(if (quoting) theme.ink else theme.surface).border(0.5.dp, theme.ruleHard, CircleShape).clickable { quoting = !quoting }.accessibilityLabel(L("note.sheet.add_quote")).padding(top = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Column(Modifier.fillMaxWidth().minePaper(theme, 14.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (quoting) {
                        Field(quote, L("note.sheet.passage"), Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(theme.inkWash).padding(horizontal = 6.dp, vertical = 4.dp)) { quote = it }
                        if (offersPage) Field(page, L("note.sheet.page"), Modifier.width(90.dp), mono = true) { page = it }
                    }
                    Field(text, "", Modifier.fillMaxWidth().heightIn(min = 110.dp).focusRequester(focus), size = 15.5f) { text = it }
                }
                error?.let { Text(it, fontSize = 11.5.sp, color = theme.inkSoft) }
                Row {
                    GridButton(L("common.cancel"), small = true, onClick = onDismiss)
                    Spacer(Modifier.weight(1f))
                    val label = if (editing != null) { if (sending) L("note.edit.saving") else L("note.edit.save") }
                        else if (sending) L("note.sheet.keeping") else L("note.sheet.keep")
                    GridButton(label, symbol = "checkmark", primary = true, small = true, enabled = !sending && (text.isNotBlank() || quote.isNotBlank())) {
                        sending = true; error = null
                        scope.launch {
                            try {
                                onKeep(text.trim(), if (quoting && quote.isNotBlank()) quote.trim() else null, if (quoting && page.isNotBlank()) page.trim() else null)
                                onDismiss()
                            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                                error = if (editing != null) NoteEdits.message(e, strings) else e.message
                            }
                            sending = false
                        }
                    }
                }
                // A note being rewritten that the member may take off.
                if (editing != null && onDelete != null) Row(
                    Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).clickable(enabled = !sending) { confirmDelete = true }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(DomainGlyphs.of("trash"), null, tint = DELETE_RED, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(L("note.edit.delete"), fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = DELETE_RED)
                }
            }
        }
    }
    if (confirmDelete && onDelete != null) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(L("note.edit.delete_confirm")) },
        text = { Text(L("note.edit.delete_hint")) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                confirmDelete = false; sending = true; error = null
                scope.launch {
                    try { onDelete(); onDismiss() } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = NoteEdits.message(e, strings) }
                    sending = false
                }
            }) { Text(L("note.edit.delete"), color = DELETE_RED) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text(L("common.cancel"), color = theme.inkSoft) } },
        containerColor = theme.surface,
    )
}

private val DELETE_RED = androidx.compose.ui.graphics.Color(0xFFC0392B)

@Composable
private fun Field(value: String, placeholder: String, modifier: Modifier, size: Float = 14.5f, mono: Boolean = false, onChange: (String) -> Unit) {
    val theme = LocalMauriceTheme.current
    val style = TextStyle(fontSize = size.sp, color = theme.ink, fontFamily = if (mono) FontFamily.Monospace else null)
    BasicTextField(value, onChange, textStyle = style, cursorBrush = SolidColor(theme.ink), modifier = modifier, decorationBox = { inner ->
        Box { if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = style.copy(color = theme.inkMute)); inner() }
    })
}

/** The shared face, written (`SharedEditor`): a title if it has one, and the text. */
@Composable
fun SharedEditor(entryTitle: String, title: String, text: String, onDismiss: () -> Unit, onSave: suspend (String?, String) -> Unit) {
    val theme = LocalMauriceTheme.current
    val scope = rememberCoroutineScope()
    var t by remember { mutableStateOf(title) }
    var body by remember { mutableStateOf(text) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(theme.bg).statusBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                GridButton(L("common.cancel"), small = true, onClick = onDismiss)
                GridLabel(L("carnet.editor.draft", entryTitle), 9.5.sp, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                GridButton(if (saving) L("note.edit.saving") else L("note.edit.save"), primary = true, small = true, enabled = !saving) {
                    saving = true; error = null
                    scope.launch {
                        try { onSave(t.trim().ifEmpty { null }, body.trim()); onDismiss() } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = e.message }
                        saving = false
                    }
                }
            }
            error?.let { Text(it, fontSize = 11.5.sp, color = theme.inkSoft, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp)) }
            Column(
                Modifier.weight(1f).padding(horizontal = 14.dp).padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(theme.surface)
                    .border(0.5.dp, theme.ruleHard, RoundedCornerShape(14.dp)).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BasicTextField(t, { t = it }, textStyle = wordmark(22.sp).copy(color = theme.ink), cursorBrush = SolidColor(theme.ink), modifier = Modifier.fillMaxWidth(), decorationBox = { inner ->
                    Box { if (t.isEmpty()) Text(L("carnet.editor.title"), style = wordmark(22.sp).copy(color = theme.inkMute)); inner() }
                })
                BasicTextField(body, { body = it }, textStyle = TextStyle(fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 25.sp, color = theme.ink), cursorBrush = SolidColor(theme.ink),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp).focusRequester(focus))
            }
        }
    }
}

/**
 * Who reads the entry with the member (`EntryShareSheet`): its owner and
 * participants, adding a member of the household, removing one, leaving.
 * [onChange] gets the new page, or null when the member left it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(session: Session, garden: GardenApi, entry: ShelfEntry, onDismiss: () -> Unit, onChange: (EntryPage?) -> Unit) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val api = LocalApi.current ?: return
    val scope = rememberCoroutineScope()
    val me = session.state.value.activeMember?.id
    var current by remember { mutableStateOf(entry) }
    var roster by remember { mutableStateOf<List<Member>>(emptyList()) }
    var rosterRead by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        roster = runCatching { eu.chezmaurice.core.settings.SettingsApi(api).members() }.getOrDefault(emptyList())
        rosterRead = true
        if (roster.isEmpty()) error = strings("entry.share.roster_failed")
    }
    val there = current.people.map { it.id }.toSet()
    val others = roster.filter { it.id != me && it.id !in there && it.id != current.owner?.id }
    fun act(id: String, work: suspend () -> Unit) {
        busy = id; error = null
        scope.launch {
            try { work() } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = e.message }
            busy = null
        }
    }
    @Composable fun row(name: String, color: String, avatar: String?, note: String?, action: (@Composable () -> Unit)?) {
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MemberBadge(name, color, absoluteUrl(api.baseUrl, avatar), 30.dp)
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = theme.ink)
            if (note != null) GridLabel(note, 9.sp)
            Spacer(Modifier.weight(1f))
            action?.invoke()
        }
        GridRule()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = theme.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GridLabel(if (current.isForeign) L("entry.share.with_you") else L("entry.share.title"))
                Text(current.title, style = wordmark(20.sp), color = theme.ink)
                Text(
                    if (current.isForeign) L("entry.share.hint.reader")
                    else L("entry.share.hint.owner"),
                    fontSize = 13.sp, color = theme.inkSoft,
                )
            }
            if (current.isShared) Column {
                GridLabel(L("entry.share.on_entry"), 9.5.sp, modifier = Modifier.padding(bottom = 6.dp))
                current.owner?.let { row(it.displayName, it.avatarColor, it.avatarUrl, L("entry.share.owner"), null) }
                for (p in current.participants.orEmpty()) row(p.displayName, p.avatarColor, p.avatarUrl, if (p.id == me) L("entry.share.you") else null) {
                    when {
                        busy == p.id -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = theme.inkMute)
                        !current.isForeign -> GridButton(L("entry.share.remove"), small = true, enabled = busy == null) {
                            act(p.id) { garden.unshare(current, p.id)?.let { current = it.entry; onChange(it) } }
                        }
                        p.id == me -> GridButton(L("entry.share.leave"), small = true, enabled = busy == null) {
                            act(p.id) { garden.unshare(current, p.id); onDismiss(); onChange(null) }
                        }
                    }
                }
            }
            if (!current.isForeign) Column {
                GridLabel(L("entry.share.add_someone"), 9.5.sp, modifier = Modifier.padding(bottom = 6.dp))
                // Nothing more to say when the roster could not be read: the error below says it.
                if (others.isEmpty() && !(rosterRead && roster.isEmpty())) Text(if (rosterRead) L("entry.share.everyone_here") else L("give.section.loading"), fontSize = 13.sp, color = theme.inkMute, modifier = Modifier.padding(vertical = 10.dp))
                for (m in others) row(m.displayName, m.avatarColor, m.avatarUrl, null) {
                    if (busy == m.id) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = theme.inkMute)
                    else GridButton(L("entry.share.add"), primary = true, small = true, enabled = busy == null) {
                        act(m.id) { val page = garden.share(current, m.id); current = page.entry; onChange(page) }
                    }
                }
            }
            // Someone in another household of this device: the entry is given
            // to them, a copy chosen element by element.
            if (!current.isForeign && !current.isLibraryOnly) OtherHouseholdsSection(session, current) { onDismiss() }
            error?.let { Text(it, fontSize = 12.5.sp, color = theme.inkSoft) }
        }
    }
}
