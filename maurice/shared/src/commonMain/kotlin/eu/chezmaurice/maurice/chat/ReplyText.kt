package eu.chezmaurice.maurice.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import eu.chezmaurice.ui.LocalMauriceTheme

/**
 * A message's Markdown, drawn — the short version of `ReplyText.swift`.
 * Server image paths are made absolute; fenced `pasted` and `article` blocks
 * (what the member pasted or shared) read as quotes.
 */
@Composable
fun ReplyText(content: String, serverUrl: String, modifier: Modifier = Modifier) {
    val theme = LocalMauriceTheme.current
    val body = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, color = theme.ink)
    Markdown(
        content = prepare(content, serverUrl),
        colors = markdownColor(text = theme.ink, codeBackground = theme.codeBg, inlineCodeBackground = theme.codeBg, dividerColor = theme.rule),
        typography = markdownTypography(
            text = body, paragraph = body,
            h1 = body.copy(fontSize = 22.sp, lineHeight = 30.sp), h2 = body.copy(fontSize = 19.sp, lineHeight = 26.sp),
            h3 = body.copy(fontSize = 17.sp), h4 = body, h5 = body, h6 = body,
            quote = body.copy(color = theme.inkSoft), bullet = body, list = body, ordered = body,
            code = body.copy(fontSize = 14.sp, color = theme.codeInk),
        ),
        imageTransformer = Coil3ImageTransformerImpl,
        modifier = modifier,
    )
}

private val FENCE = Regex("""(?m)^(`{3,})(pasted|article)[ \t]*\n([\s\S]*?)\n\1[ \t]*$""")
private val SERVER_IMAGE = Regex("""\]\((/api/[^)\s]+)\)""")

internal fun prepare(content: String, serverUrl: String): String {
    val base = serverUrl.trimEnd('/')
    return content
        .replace(FENCE) { match -> match.groupValues[3].lines().joinToString("\n") { "> $it" } }
        .replace(SERVER_IMAGE) { "](" + base + it.groupValues[1] + ")" }
        .replace("![photo](pending)", "")
}
