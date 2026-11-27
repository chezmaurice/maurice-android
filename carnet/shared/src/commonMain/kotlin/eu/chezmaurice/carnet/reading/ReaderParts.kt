package eu.chezmaurice.carnet.reading

import eu.chezmaurice.ui.L
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import eu.chezmaurice.ui.accessibilityHidden
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.carnet.platform.Offline
import eu.chezmaurice.carnet.ui.GridButton
import eu.chezmaurice.carnet.ui.GridIconButton
import eu.chezmaurice.carnet.ui.GridLabel
import eu.chezmaurice.carnet.ui.GridSegments
import eu.chezmaurice.carnet.ui.GridSheet
import eu.chezmaurice.core.carnet.HIGHLIGHT_COLORS
import eu.chezmaurice.core.carnet.HighlightConversation
import eu.chezmaurice.carnet.entry.MauriceMark
import eu.chezmaurice.carnet.platform.rememberMauriceApp
import eu.chezmaurice.ui.accessibilityName
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.wordmark

/** The reading size, one of [READER_SIZES], shared by both readers and kept on the device. */
object ReaderSize {
    fun get(): Int = Offline.read("reader-size")?.trim()?.toIntOrNull()?.coerceIn(0, READER_SIZES.size - 1) ?: 1
    fun next(): Int = ((get() + 1) % READER_SIZES.size).also { Offline.write("reader-size", it.toString()) }
}

/**
 * A reader's bar, out of the way while the text is read: slid off its edge
 * ([top] or bottom) and faded. It floats over the text, which does not move.
 */
@Composable
fun Modifier.readerChrome(hidden: Boolean, top: Boolean): Modifier {
    val away by androidx.compose.animation.core.animateFloatAsState(if (hidden) 1f else 0f, androidx.compose.animation.core.tween(220))
    return graphicsLayer {
        translationY = away * (if (top) -size.height else size.height)
        alpha = 1f - away
    }.accessibilityHidden(hidden)
}

/** Eyebrow, serif title, meta line: the first thing in the text's own flow, scrolling away with it. */
@Composable
fun ReaderHeading(eyebrow: String, title: String, meta: String?, titleLines: Int = 2, inset: Dp = 24.dp) {
    val theme = LocalMauriceTheme.current
    Column(Modifier.fillMaxWidth().padding(horizontal = inset).padding(top = 10.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GridLabel(eyebrow)
        Text(title, style = wordmark(26.sp), color = theme.ink, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
        if (meta != null) GridLabel(meta, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun Text(text: String, style: TextStyle, color: androidx.compose.ui.graphics.Color, maxLines: Int, overflow: TextOverflow) =
    androidx.compose.material3.Text(text, style = style, color = color, maxLines = maxLines, overflow = overflow)

/** Text | Summary. */
@Composable
fun ReaderModeSwitch(summary: Boolean, summaryEnabled: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Box(modifier) {
        GridSegments(listOf("full" to L("carnet.reader.text"), "summary" to L("carnet.summary")), if (summary) "summary" else "full", enabled = { it == "full" || summaryEnabled }) { onChange(it == "summary") }
    }
}

/** Aa: cycles the three sizes. */
@Composable
fun ReaderSizeButton(onChange: (Int) -> Unit) = GridIconButton(DomainGlyphs.of("textformat.size"), L("carnet.reader.size")) { onChange(ReaderSize.next()) }

/**
 * A highlight, made or opened (`HighlightEditorSheet`): its colour, a note,
 * and on an existing one, Delete.
 */
@Composable
fun HighlightEditor(
    quote: String, color: String, note: String, editing: Boolean, onDismiss: () -> Unit,
    onSave: (String, String, eu.chezmaurice.core.carnet.LinkTarget?) -> Unit, onDelete: (() -> Unit)? = null,
    /** Offers "Link to…" (a résonance): creation only, an edited one's already lives in the fiche. */
    canLink: Boolean = false,
    /** The passage's conversation with Maurice, when it has one: what it came to, and the way back to it. */
    conversation: HighlightConversation? = null,
    /** When set, "Talk it over with Maurice": the highlight is saved as it stands, then the conversation opens. */
    onTalk: ((String, String, eu.chezmaurice.core.carnet.LinkTarget?) -> Unit)? = null,
) {
    val theme = LocalMauriceTheme.current
    val maurice = rememberMauriceApp()
    var c by remember { mutableStateOf(color) }
    var n by remember { mutableStateOf(note) }
    var target by remember { mutableStateOf<eu.chezmaurice.core.carnet.LinkTarget?>(null) }
    var picking by remember { mutableStateOf(false) }
    GridSheet(onDismiss) {
        GridLabel(L("carnet.highlight.title"))
        androidx.compose.material3.Text(
            quote, fontSize = 14.sp, color = theme.ink, maxLines = 8, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(highlightColor(c, theme.ink, theme.isDark)).padding(10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (key in HIGHLIGHT_COLORS) Box(
                Modifier.size(26.dp).clip(CircleShape).background(highlightColor(key, theme.ink, theme.isDark)).border(if (key == c) 2.dp else 0.5.dp, if (key == c) theme.ink else theme.ruleHard, CircleShape)
                    .clickable { c = key },
            )
        }
        GridLabel(L("carnet.highlight.note"), 9.sp)
        BasicTextField(
            n, { n = it }, textStyle = TextStyle(fontSize = 15.sp, color = theme.ink), cursorBrush = SolidColor(theme.ink),
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(RoundedCornerShape(10.dp)).background(theme.bg).border(0.5.dp, theme.ruleHard, RoundedCornerShape(10.dp)).padding(10.dp),
        )
        // A résonance: the passage and the note filed on another entry's fiche, with a link back here.
        if (canLink && !editing) LinkTargetField(target, onOpen = { picking = true }, onClear = { target = null })
        if (conversation != null) TalkedOver(conversation, maurice)
        // The Maurice app opens the conversation: without it (the desktop), no offer.
        else if (onTalk != null && maurice.installed) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(0.5.dp, theme.ruleHard, RoundedCornerShape(10.dp))
                .clickable { onTalk(c, n.trim(), target); onDismiss() }.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            MauriceMark(22.dp)
            androidx.compose.material3.Text(L("carnet.highlight.talk"), fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = theme.ink, modifier = Modifier.weight(1f))
            androidx.compose.material3.Icon(DomainGlyphs.of("arrow.up.right"), null, tint = theme.inkMute, modifier = Modifier.size(13.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (editing && onDelete != null) GridButton(L("carnet.delete"), small = true) { onDelete(); onDismiss() }
            Spacer(Modifier.weight(1f))
            GridButton(L("common.cancel"), small = true, onClick = onDismiss)
            Spacer(Modifier.width(8.dp))
            GridButton(L("note.edit.save"), primary = true, small = true) { onSave(c, n.trim(), target); onDismiss() }
        }
    }
    if (picking) LinkTargetPicker(onDismiss = { picking = false }) { target = it }
}

/**
 * What the conversation opened from a passage came to, and the way back to
 * it. The summary is the one on the entry's fiche, written once the
 * conversation has settled.
 */
@Composable
private fun TalkedOver(c: HighlightConversation, maurice: eu.chezmaurice.carnet.platform.MauriceApp) {
    val theme = LocalMauriceTheme.current
    val server = eu.chezmaurice.carnet.ui.LocalApi.current?.baseUrl.orEmpty()
    Column(
        Modifier.fillMaxWidth().border(0.5.dp, theme.ruleHard, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MauriceMark(18.dp)
            GridLabel(L("carnet.highlight.talked_over"), 9.sp, modifier = Modifier.weight(1f))
            if (maurice.installed) Box(
                Modifier.size(32.dp, 28.dp).clip(RoundedCornerShape(6.dp)).clickable { maurice.open(c.id) }.accessibilityName(L("carnet.highlight.open_in_maurice")),
                contentAlignment = Alignment.Center,
            ) { androidx.compose.material3.Icon(DomainGlyphs.of("arrow.up.right"), null, tint = theme.inkSoft, modifier = Modifier.size(14.dp)) }
        }
        val summary = c.summary
        if (summary != null) Box(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            eu.chezmaurice.maurice.chat.ReplyText(summary, server)
        } else androidx.compose.material3.Text(L("carnet.highlight.settling"), fontSize = 12.5.sp, color = theme.inkMute)
    }
}

/** What the highlight editor is open on. */
sealed interface HighlightEdit {
    data class New(val start: Int, val end: Int, val quote: String) : HighlightEdit
    data class Existing(val highlight: eu.chezmaurice.core.carnet.Highlight) : HighlightEdit
}

@Composable
fun HighlightEditFor(
    edit: HighlightEdit, onDismiss: () -> Unit,
    onCreate: (HighlightEdit.New, String, String) -> Unit,
    onUpdate: (eu.chezmaurice.core.carnet.Highlight, String, String) -> Unit,
    onDelete: (eu.chezmaurice.core.carnet.Highlight) -> Unit,
    /** Where the passage is read: offered as the source of a résonance. Null: no "Link to…". */
    resonance: eu.chezmaurice.core.carnet.ResonanceSource? = null,
    /** Sent by the reader, whose scope outlives the sheet. */
    onLink: (eu.chezmaurice.core.carnet.LinkTarget, String, String, eu.chezmaurice.core.carnet.ResonanceSource) -> Unit = { _, _, _, _ -> },
    /** "Talk it over with Maurice" on a passage being made: saved first, then talked over. Null: no offer. */
    onTalkNew: ((HighlightEdit.New, String, String) -> Unit)? = null,
    /** The same on a passage already kept: its colour and note saved if changed, then talked over. */
    onTalkExisting: ((eu.chezmaurice.core.carnet.Highlight, String, String) -> Unit)? = null,
) {
    when (edit) {
        is HighlightEdit.New -> HighlightEditor(edit.quote, "yellow", "", false, onDismiss, canLink = resonance != null, onSave = { c, n, target ->
            onCreate(edit, c, n)
            if (target != null && resonance != null) onLink(target, n, edit.quote, resonance)
        }, onTalk = onTalkNew?.let { talk -> { c, n, target ->
            talk(edit, c, n)
            if (target != null && resonance != null) onLink(target, n, edit.quote, resonance)
        } })
        is HighlightEdit.Existing -> HighlightEditor(edit.highlight.quote, edit.highlight.color, edit.highlight.note.orEmpty(), true, onDismiss,
            onSave = { c, n, _ -> onUpdate(edit.highlight, c, n) }, onDelete = { onDelete(edit.highlight) },
            conversation = edit.highlight.conversation
                ?: edit.highlight.conversationId?.let { HighlightConversation(it) },
            onTalk = onTalkExisting?.let { talk -> { c, n, _ -> talk(edit.highlight, c, n) } })
    }
}
