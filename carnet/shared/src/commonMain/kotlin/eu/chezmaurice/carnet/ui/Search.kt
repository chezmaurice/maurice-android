package eu.chezmaurice.carnet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.ui.LocalMauriceTheme

private val FOLDS = mapOf(
    "àáâãäåā" to 'a', "çćč" to 'c', "èéêëēė" to 'e', "ìíîïī" to 'i', "ñń" to 'n',
    "òóôõöøō" to 'o', "ùúûüū" to 'u', "ýÿ" to 'y', "śš" to 's', "źżž" to 'z', "ł" to 'l',
).flatMap { (from, to) -> from.map { it to to } }.toMap()

/** Lower case, accents aside: what a search compares (`.caseInsensitive, .diacriticInsensitive`). */
fun String.folded(): String = buildString(length) {
    for (c in this@folded.lowercase()) when (c) {
        'œ' -> append("oe"); 'æ' -> append("ae"); 'ß' -> append("ss")
        else -> append(FOLDS[c] ?: c)
    }
}

/** Every word of [query] is somewhere in [hay], case and accents aside. */
fun matchesAll(query: String, hay: String): Boolean {
    val words = query.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.folded() }
    if (words.isEmpty()) return true
    val folded = hay.folded()
    return words.all { folded.contains(it) }
}

/** The search capsule under a page's title; focused as it appears. */
@Composable
fun SearchField(query: String, placeholder: String, onChange: (String) -> Unit) {
    val theme = LocalMauriceTheme.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.padding(horizontal = PAGE).padding(bottom = 14.dp).fillMaxWidth().height(40.dp).clip(CircleShape).background(theme.surface)
            .border(0.5.dp, theme.ruleHard, CircleShape).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = theme.inkMute, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            query, onChange, singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = theme.ink), cursorBrush = SolidColor(theme.ink),
            modifier = Modifier.weight(1f).focusRequester(focus),
            decorationBox = { inner -> if (query.isEmpty()) Text(placeholder, fontSize = 15.sp, color = theme.inkMute, maxLines = 1); inner() },
        )
    }
}
