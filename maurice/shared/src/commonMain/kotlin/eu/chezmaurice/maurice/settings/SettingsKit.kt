package eu.chezmaurice.maurice.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.maurice.chat.legible
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.wordmark

// The building blocks of the "index card" settings (`SettingsView.swift`):
// a quiet root that reads like a table of contents, each row showing its
// current value, and detail panes one tap away.

@Composable
fun SetHeader(title: String, onClose: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = wordmark(24.sp), color = theme.ink, modifier = Modifier.weight(1f))
            CloseButton(onClose)
        }
        SetDivider()
    }
}

@Composable
fun SetDetailHeader(title: String, back: String, onBack: () -> Unit, onClose: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = legible(LocalAccent.current, theme.isDark)
    Column(Modifier.padding(start = 18.dp, end = 16.dp, top = 16.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onBack).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = accent, modifier = Modifier.size(18.dp))
                Text(back, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = accent)
            }
            Spacer(Modifier.weight(1f))
            CloseButton(onClose)
        }
        Text(title, style = wordmark(22.sp), color = theme.ink)
    }
}

@Composable
fun CloseButton(onClose: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Box(Modifier.size(30.dp).clip(CircleShape).background(theme.surfaceAlt).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Close, null, tint = theme.inkSoft, modifier = Modifier.size(15.dp))
    }
}

@Composable
fun SetDivider() = HorizontalDivider(thickness = 0.5.dp, color = LocalMauriceTheme.current.rule)

@Composable
fun SetCard(content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalMauriceTheme.current
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(theme.surfaceAlt).border(0.5.dp, theme.rule, shape), content = content)
}

@Composable
fun SetGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SetKicker(title)
        content()
    }
}

@Composable
fun SetKicker(text: String) =
    Text(text.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, letterSpacing = 1.2.sp, color = LocalMauriceTheme.current.inkMute)

@Composable
fun SetCaption(text: String) = Text(text, fontSize = 11.5.sp, color = LocalMauriceTheme.current.inkMute)

/** One row of the index: its glyph on the accent, its label, its value, a chevron (or [trailing]). */
@Composable
fun IndexRow(symbol: String, label: String, value: String? = null, valueMono: Boolean = false, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(DomainGlyphs.of(symbol), null, tint = legible(accent, theme.isDark), modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(11.dp))
        Text(label, fontSize = 13.5.sp, color = theme.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        if (value != null) Text(
            value, fontSize = if (valueMono) 11.sp else 12.5.sp, fontFamily = if (valueMono) FontFamily.Monospace else null,
            color = theme.inkMute, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp),
        )
        if (trailing != null) { Spacer(Modifier.width(6.dp)); trailing() }
        else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = theme.inkMute.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
    }
}

/** A choice among several: checked when on. */
@Composable
fun SetCheckRow(label: String, on: Boolean, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 42.dp).background(if (on) accent.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, color = theme.ink, modifier = Modifier.weight(1f))
        if (on) Icon(Icons.Default.Check, null, tint = legible(accent, theme.isDark), modifier = Modifier.size(16.dp))
    }
}

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))
