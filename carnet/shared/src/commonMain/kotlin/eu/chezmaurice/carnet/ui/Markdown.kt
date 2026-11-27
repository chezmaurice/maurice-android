package eu.chezmaurice.carnet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.carnet.EntryDetail
import eu.chezmaurice.ui.LocalMauriceTheme

/**
 * Inline Markdown as styled text: **bold**, *italic* / _italic_, `code`,
 * [links](url), and the garden's [[basename|label]] — the entry it names,
 * opened in Carnet ([onWiki]) published or not; else its published page;
 * else an underline.
 */
fun inlineMarkdown(source: String, ink: Color, links: List<EntryDetail.Link> = emptyList(), onWiki: ((EntryDetail.Link) -> Unit)? = null): AnnotatedString = buildAnnotatedString {
    var i = 0
    val s = source
    fun plain(c: Char) { append(c); i++ }
    while (i < s.length) {
        when {
            s.startsWith("[[", i) -> {
                val end = s.indexOf("]]", i + 2)
                if (end < 0) { plain(s[i]); continue }
                val inner = s.substring(i + 2, end)
                val basename = inner.substringBefore('|')
                val label = inner.substringAfter('|', basename)
                val link = links.firstOrNull { it.basename == basename }
                val published = link?.publishedUrl
                val style = TextLinkStyles(SpanStyle(color = ink, textDecoration = TextDecoration.Underline))
                if (link?.entryId != null && onWiki != null) withLink(LinkAnnotation.Clickable("wiki:$basename", style) { onWiki(link) }) { append(label) }
                else if (published != null) withLink(LinkAnnotation.Url(published, TextLinkStyles(SpanStyle(color = ink, textDecoration = TextDecoration.Underline)))) { append(label) }
                else withStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = ink.copy(alpha = 0.85f))) { append(label) }
                i = end + 2
            }
            s[i] == '[' -> {
                val close = s.indexOf("](", i + 1)
                val end = if (close > 0) s.indexOf(')', close + 2) else -1
                if (close < 0 || end < 0 || s.substring(i + 1, close).contains('\n')) { plain(s[i]); continue }
                val label = s.substring(i + 1, close)
                val url = s.substring(close + 2, end)
                withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = ink, textDecoration = TextDecoration.Underline)))) { append(inlineMarkdown(label, ink)) }
                i = end + 1
            }
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end < 0) { plain(s[i]); continue }
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inlineMarkdown(s.substring(i + 2, end), ink, links, onWiki)) }
                i = end + 2
            }
            (s[i] == '*' || s[i] == '_') && i + 1 < s.length && !s[i + 1].isWhitespace() -> {
                val mark = s[i]
                val end = s.indexOf(mark, i + 1)
                // An underscore inside a word is no emphasis.
                if (end < 0 || (mark == '_' && i > 0 && s[i - 1].isLetterOrDigit())) { plain(s[i]); continue }
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inlineMarkdown(s.substring(i + 1, end), ink, links, onWiki)) }
                i = end + 1
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end < 0) { plain(s[i]); continue }
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(s.substring(i + 1, end)) }
                i = end + 1
            }
            else -> plain(s[i])
        }
    }
}

/**
 * Blocks of Markdown as Carnet sets them (`MarkdownBlocks`): paragraphs,
 * headings in bold, quotes with a rule; serif for the shared face.
 */
@Composable
fun MarkdownBlocks(text: String, serif: Boolean, links: List<EntryDetail.Link> = emptyList(), maxLines: Int = Int.MAX_VALUE) {
    val theme = LocalMauriceTheme.current
    // A [[link]] opens the entry it names, in Carnet.
    val onWiki = eu.chezmaurice.carnet.reading.LocalOpenWikiLink.current
    val blocks = text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() && it != "---" }
    val family = if (serif) FontFamily.Serif else null
    val size = if (serif) 16.sp else 14.5.sp
    Column(verticalArrangement = Arrangement.spacedBy(if (serif) 13.dp else 10.dp), modifier = Modifier.fillMaxWidth()) {
        for (block in blocks) when {
            block.startsWith("#") -> Text(inlineMarkdown(block.trimStart('#', ' '), theme.ink, links, onWiki), fontFamily = family, fontSize = size, fontWeight = FontWeight.SemiBold, color = theme.ink)
            block.startsWith(">") -> Row(Modifier.height(IntrinsicSize.Min)) {
                Spacer(Modifier.width(1.5.dp).fillMaxHeight().background(theme.ruleHard))
                Spacer(Modifier.width(10.dp))
                Text(inlineMarkdown(block.lines().joinToString("\n") { it.trimStart('>', ' ') }, theme.ink, links, onWiki), fontFamily = family, fontSize = size, color = theme.inkSoft)
            }
            else -> {
                val listed = block.startsWith("- ") || block.startsWith("* ") || block.first().isDigit()
                Text(
                    inlineMarkdown(if (listed) block else block.replace("\n", " "), theme.ink, links, onWiki), fontFamily = family, fontSize = size, color = theme.ink,
                    lineHeight = size * (if (serif) 1.45f else 1.35f), maxLines = maxLines, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
