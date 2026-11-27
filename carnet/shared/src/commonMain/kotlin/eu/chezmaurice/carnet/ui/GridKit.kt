package eu.chezmaurice.carnet.ui

import eu.chezmaurice.ui.L
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.carnet.MemberBrief
import eu.chezmaurice.ui.Avatar
import eu.chezmaurice.ui.accessibilityName
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.wordmark

// Carnet's building blocks (`GridKit.swift`): one warm ink on a warm paper,
// no accent colour — the one primary action is ink with the paper as its
// text — hairlines rather than boxes, mono eyebrows.

/** Page margin. */
val PAGE = 20.dp

/** A mono, uppercase eyebrow. */
@Composable
fun GridLabel(text: String, size: TextUnit = 10.sp, color: Color = LocalMauriceTheme.current.inkMute, weight: FontWeight = FontWeight.Medium, underline: Boolean = false, modifier: Modifier = Modifier) =
    Text(
        text.uppercase(), fontFamily = FontFamily.Monospace, fontSize = size, fontWeight = weight, letterSpacing = (size.value * 0.12f).sp,
        color = color, maxLines = 1, textDecoration = if (underline) TextDecoration.Underline else null, modifier = modifier,
    )

@Composable
fun GridRule(hard: Boolean = false, modifier: Modifier = Modifier) {
    val theme = LocalMauriceTheme.current
    HorizontalDivider(modifier, thickness = 0.5.dp, color = if (hard) theme.ruleHard else theme.rule)
}

/** A thin progress bar: ink, sage once full. */
@Composable
fun GridBar(value: Double, width: Dp? = null, height: Dp = 2.dp, accentWhenFull: Boolean = true) {
    val theme = LocalMauriceTheme.current
    val v = value.coerceIn(0.0, 1.0).toFloat()
    Box((if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).height(height).clip(CircleShape).background(theme.ruleHard)) {
        Box(Modifier.fillMaxWidth(v).height(height).clip(CircleShape).background(if (accentWhenFull && v >= 1f) theme.good else theme.ink))
    }
}

/** The letter of a title, where its cover would be. */
@Composable
fun GridCover(letter: String, width: Dp = 46.dp, height: Dp = 64.dp) {
    val theme = LocalMauriceTheme.current
    val shape = RoundedCornerShape(3.dp)
    Box(Modifier.size(width, height).clip(shape).background(theme.surfaceAlt).border(0.5.dp, theme.rule, shape), contentAlignment = Alignment.Center) {
        Text(letter, style = wordmark((height.value * 0.3f).sp), color = theme.inkMute)
    }
}

/** Draft (a ring) or published (a dot). */
@Composable
fun StateMark(published: Boolean) {
    val theme = LocalMauriceTheme.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (published) theme.ink else Color.Transparent).border(1.25.dp, if (published) theme.ink else theme.inkSoft, CircleShape))
        GridLabel(if (published) L("carnet.state.published") else L("carnet.state.draft"), 9.5.sp, if (published) theme.ink else theme.inkSoft)
    }
}

/** A capsule button; [primary] is filled with ink; [destructive] writes in red. */
@Composable
fun GridButton(title: String, symbol: String? = null, primary: Boolean = false, full: Boolean = false, small: Boolean = false, enabled: Boolean = true, description: String? = null, destructive: Boolean = false, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val filled = primary && enabled
    Row(
        (if (full) Modifier.fillMaxWidth() else Modifier).clip(CircleShape)
            .background(if (filled) theme.ink else if (primary) theme.ink.copy(alpha = 0.07f) else Color.Transparent)
            .border(0.5.dp, if (filled) Color.Transparent else theme.ruleHard, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.accessibilityName(description) else Modifier)
            .padding(horizontal = if (small) 14.dp else 18.dp, vertical = if (small) 9.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        val color = if (!enabled) theme.inkMute else if (primary) theme.bg else if (destructive) Color(0xFFC0392B) else theme.ink
        if (symbol != null) Icon(DomainGlyphs.of(symbol), null, tint = color, modifier = Modifier.size(if (small) 13.dp else 15.dp))
        Text(title, fontSize = if (small) 12.5.sp else 14.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

/** A round glyph button; [on] fills it with ink. */
@Composable
fun GridIconButton(icon: ImageVector, description: String?, on: Boolean = false, size: Dp = 40.dp, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Box(
        modifier.size(size).clip(CircleShape).background(if (on) theme.ink else theme.surface).border(0.5.dp, if (on) theme.ink else theme.ruleHard, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = if (on) theme.bg else theme.inkSoft, modifier = Modifier.size(size * 0.46f)) }
}

/** A filter chip with its count; ticked when on. */
@Composable
fun KindChip(label: String, count: Int, on: Boolean, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Row(
        Modifier.height(34.dp).clip(CircleShape).border(1.25.dp, if (on) theme.ink else Color.Transparent, CircleShape).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (on) Text("✓", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.ink)
        Text(label, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) theme.ink else theme.inkSoft)
        Text("$count", fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, color = theme.inkMute)
    }
}

/** A member's face, from their photo or their initial on their colour. */
@Composable
fun MemberBadge(name: String, color: String, avatarUrl: String?, size: Dp) = Avatar(name, color, avatarUrl, size = size)

/** Several members, overlapping. */
@Composable
fun AvatarStack(people: List<MemberBrief>, serverUrl: String, size: Dp = 16.dp) {
    Box(Modifier.width(size + (size * 0.6f) * (people.size - 1).coerceAtLeast(0)).height(size)) {
        people.forEachIndexed { i, p ->
            Box(Modifier.offset(x = size * 0.6f * i).border(1.dp, LocalMauriceTheme.current.bg, CircleShape)) {
                MemberBadge(p.displayName, p.avatarColor, eu.chezmaurice.maurice.absoluteUrl(serverUrl, p.avatarUrl), size)
            }
        }
    }
}

@Composable
fun GridLoading(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = LocalMauriceTheme.current.inkMute)
}

@Composable
fun GridMessage(text: String, modifier: Modifier = Modifier) {
    val theme = LocalMauriceTheme.current
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("⚠", fontSize = 24.sp, color = theme.inkMute)
        Text(text, fontSize = 14.sp, color = theme.inkSoft, textAlign = TextAlign.Center)
    }
}

/** The big serif title of a page, with its eyebrow. */
@Composable
fun GridTitle(title: String, kicker: String? = null, right: @Composable () -> Unit = {}) {
    val theme = LocalMauriceTheme.current
    Column(Modifier.fillMaxWidth().padding(horizontal = PAGE).padding(top = 4.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (kicker != null) GridLabel(kicker, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f))
            right()
        }
        Text(title, style = wordmark(32.sp), color = theme.ink)
    }
}

/** Two choices side by side; the chosen one raised. */
@Composable
fun GridSegments(options: List<Pair<String, String>>, value: String, enabled: (String) -> Boolean = { true }, onChange: (String) -> Unit) {
    val theme = LocalMauriceTheme.current
    Row(Modifier.clip(CircleShape).background(theme.rule).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((v, label) in options) {
            val on = v == value
            Box(
                Modifier.weight(1f).height(34.dp).clip(CircleShape).background(if (on) theme.surface else Color.Transparent)
                    .clickable(enabled = enabled(v)) { onChange(v) }.alpha(if (enabled(v)) 1f else 0.5f),
                contentAlignment = Alignment.Center,
            ) { Text(label, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) theme.ink else theme.inkSoft) }
        }
    }
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun FullScreen(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize().background(LocalMauriceTheme.current.bg)) { content() }
