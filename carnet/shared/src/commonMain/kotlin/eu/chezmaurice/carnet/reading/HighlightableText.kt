package eu.chezmaurice.carnet.reading

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle as UiTextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import eu.chezmaurice.carnet.platform.HighlightMenu
import eu.chezmaurice.core.carnet.Highlight
import eu.chezmaurice.core.carnet.TextStyle
import eu.chezmaurice.ui.LocalMauriceTheme
import kotlinx.coroutines.flow.distinctUntilChanged

/** A highlight's wash: the ink's for the default ("yellow"), a tint for the three others. */
fun highlightColor(key: String, ink: Color, dark: Boolean): Color = when (key) {
    "green" -> Color(0.60f, 0.80f, 0.52f, 0.40f)
    "pink" -> Color(0.93f, 0.60f, 0.70f, 0.38f)
    "blue" -> Color(0.55f, 0.68f, 0.90f, 0.36f)
    else -> ink.copy(alpha = if (dark) 0.16f else 0.11f)
}

/** The three reading sizes, shared by both readers. */
val READER_SIZES = listOf(15f, 17f, 19.5f)

/**
 * A reading text with its highlights washed in, selectable, with
 * "Highlight" in the selection's menu (`HighlightableText`). The offsets it
 * hands out and takes are the text's own string indices — UTF-16 units, as
 * on the server and on iOS. It brings [initialChar] to the top once laid
 * out, and reports the character at the top as the reader scrolls; a size
 * or highlight change keeps that character where it was.
 */
@Composable
fun HighlightableText(
    text: String,
    fontSize: Float,
    highlights: List<Highlight>,
    styles: List<TextStyle> = emptyList(),
    initialChar: Int = 0,
    /** The margin on each side of the text; the scroll runs edge to edge. */
    sideInset: Dp = 24.dp,
    bottomInset: Dp = 28.dp,
    /** Room above the text for the reader's bar, which floats over it: the text scrolls beneath. */
    topInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    onTopChar: (Int) -> Unit = {},
    /**
     * Whether the reader's bars should show: false once the reader scrolls on
     * into the text, true when they scroll back, at the top, at the end. A
     * new text starts with them shown.
     */
    onChromeVisible: ((Boolean) -> Unit)? = null,
    onCreate: (start: Int, end: Int, quote: String) -> Unit,
    onTapHighlight: (Highlight) -> Unit,
) {
    val theme = LocalMauriceTheme.current
    val scroll = remember(text) { androidx.compose.foundation.ScrollState(0) }
    var selection by remember(text) { mutableStateOf(TextRange.Zero) }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    var textTop by remember(text) { mutableFloatStateOf(0f) }
    /** The character to bring to the top at the next layout. */
    var pending by remember(text) { mutableStateOf(initialChar.takeIf { it > 0 }) }
    var restored by remember(text) { mutableStateOf(initialChar <= 0) }
    var top by remember(text) { mutableStateOf(initialChar) }
    val report by rememberUpdatedState(onTopChar)
    val tapped by rememberUpdatedState(onTapHighlight)

    val annotated = remember(text, highlights, styles, fontSize, theme) {
        buildAnnotatedString {
            append(text)
            val n = text.length
            // Blank lines between paragraphs: a short gap, not a full line.
            var offset = 0
            for (line in text.split("\n")) {
                val end = offset + line.length
                if (line.isBlank() && end < n) addStyle(SpanStyle(fontSize = (fontSize * 0.4f).sp), offset, end + 1)
                offset = end + 1
            }
            for (s in styles) {
                if (s.start < 0 || s.end > n || s.start >= s.end) continue
                when (s.kind) {
                    "heading" -> addStyle(SpanStyle(fontSize = (fontSize * 1.12f).sp, fontWeight = FontWeight.SemiBold), s.start, s.end)
                    "strong" -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), s.start, s.end)
                    "em" -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), s.start, s.end)
                }
            }
            for (h in highlights) {
                val s = h.startOffset ?: continue
                val e = h.endOffset ?: continue
                if (s < 0 || e > n || s >= e) continue
                addStyle(SpanStyle(background = highlightColor(h.color, theme.ink, theme.isDark)), s, e)
            }
        }
    }

    // A new size or a new highlight lays the text out again: keep the
    // character at the top where it was.
    LaunchedEffect(fontSize, highlights.size) { if (restored && top > 0) pending = top }

    LaunchedEffect(layout, pending, textTop) {
        val l = layout ?: return@LaunchedEffect
        val char = pending ?: return@LaunchedEffect
        val line = l.getLineForOffset(char.coerceIn(0, maxOf(0, text.length - 1)))
        scroll.scrollTo((textTop + l.getLineTop(line)).toInt())
        pending = null
        restored = true
    }

    // Down into the text hides the bars, back up shows them; the top and the
    // end of the text always show them. A jitter of a few points does not count.
    val chrome by rememberUpdatedState(onChromeVisible)
    val density = LocalDensity.current
    LaunchedEffect(text) {
        val edge = with(density) { 24.dp.toPx() }
        val enough = with(density) { 28.dp.toPx() }
        var lastY: Int? = null
        var travel = 0f
        var shown = true
        snapshotFlow { scroll.value }.collect { y ->
            val last = lastY
            lastY = y
            val report = chrome ?: return@collect
            if (!restored || pending != null || !scroll.isScrollInProgress || last == null) return@collect
            val show: Boolean? = if (y <= edge || y >= scroll.maxValue - edge) true else {
                val dy = (y - last).toFloat()
                travel = if ((dy > 0) == (travel > 0)) travel + dy else dy
                if (travel > enough) false else if (travel < -enough) true else null
            }
            if (show != null && show != shown) { shown = show; travel = 0f; report(show) }
        }
    }

    LaunchedEffect(text) {
        snapshotFlow { scroll.value }.distinctUntilChanged().collect { y ->
            val l = layout ?: return@collect
            if (!restored || pending != null) return@collect
            val at = l.getOffsetForPosition(Offset(4f, (y - textTop).coerceAtLeast(0f) + 1f))
            top = at
            report(at)
        }
    }

    val focus = LocalFocusManager.current
    HighlightMenu(available = !selection.collapsed, onHighlight = {
        val s = selection.min
        val e = selection.max
        if (e > s) onCreate(s, e, text.substring(s, e))
        // Let the selection and its menu go.
        selection = TextRange(e)
        focus.clearFocus()
    }) {
        Column(modifier.fillMaxSize().verticalScroll(scroll)) {
            if (topInset > 0.dp) Spacer(Modifier.height(topInset))
            header?.let { Column(Modifier.padding(horizontal = sideInset)) { it() } }
            BasicTextField(
                value = TextFieldValue(annotated, selection),
                onValueChange = { v ->
                    val was = selection
                    selection = v.selection
                    // A tap, not the release of a selection: on a highlight, open it.
                    if (v.selection.collapsed && was.collapsed) {
                        highlights.firstOrNull { it.covers(v.selection.start) }?.let { focus.clearFocus(); tapped(it) }
                    }
                },
                readOnly = true,
                textStyle = UiTextStyle(fontSize = fontSize.sp, lineHeight = 1.55.em, color = theme.ink, letterSpacing = (-0.2).sp),
                cursorBrush = SolidColor(Color.Transparent),
                onTextLayout = { layout = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = sideInset).onGloballyPositioned { textTop = it.positionInParent().y },
            )
            Spacer(Modifier.height(bottomInset))
        }
    }
}
