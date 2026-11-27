package eu.chezmaurice.maurice.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.carnet.EpisodeList
import eu.chezmaurice.core.model.ConversationEntry
import eu.chezmaurice.maurice.budget.longDate
import eu.chezmaurice.maurice.consent.LocalAppLanguage
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.MauriceIcons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon

/**
 * A show's recent episodes, laid out as a podcast app lists them — the day it
 * came out, the title, what it is about, its length, its art — the one Maurice
 * reads the conversation as being about ticked in advance (`EpisodePicker`).
 * Each is ticked on its own; none ticked leaves the show itself to be kept.
 */
@Composable
internal fun EpisodePicker(
    feed: String?,
    serverUrl: String,
    onEpisodes: suspend (String?) -> EpisodeList?,
    selection: List<String>,
    onSelection: (List<String>) -> Unit,
) {
    val theme = LocalMauriceTheme.current
    val language = LocalAppLanguage.current
    var list by remember { mutableStateOf<EpisodeList?>(null) }
    var loading by remember { mutableStateOf(true) }
    var shown by remember { mutableIntStateOf(5) }
    LaunchedEffect(Unit) {
        val got = onEpisodes(feed)
        list = got
        val at = got?.suggested?.let { s -> got.episodes.indexOfFirst { it.id == s } } ?: -1
        if (at >= 0) { onSelection(listOf(got!!.suggested!!)); shown = maxOf(shown, at + 1) } else onSelection(emptyList())
        loading = false
    }
    Column {
        val episodes = list?.episodes.orEmpty()
        when {
            loading -> Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = theme.inkMute)
                Spacer(Modifier.width(8.dp))
                Text(L("suggest.episodes_loading"), fontSize = 12.sp, color = theme.inkMute)
            }
            episodes.isNotEmpty() -> {
                Text(L("suggest.episodes").uppercase(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = theme.inkMute, modifier = Modifier.padding(bottom = 4.dp))
                episodes.take(shown).forEachIndexed { i, ep ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(theme.rule))
                    val on = ep.id in selection
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelection(if (on) selection - ep.id else selection + ep.id) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            Modifier.padding(top = 2.dp).size(16.dp).clip(RoundedCornerShape(4.dp))
                                .then(if (on) Modifier.background(theme.ink) else Modifier.border(1.5.dp, theme.inkMute, RoundedCornerShape(4.dp))),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Text("✓", fontSize = 11.sp, color = theme.bg) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ep.date?.let { longDate(it, language) }?.let { Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = theme.inkMute) }
                                if (ep.id == list?.suggested) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(L("suggest.episode_suggested"), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = theme.inkSoft,
                                        modifier = Modifier.clip(RoundedCornerShape(50)).background(theme.inkMute.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 1.dp))
                                }
                            }
                            Text(ep.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = theme.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (ep.description.isNotEmpty()) Text(ep.description, fontSize = 12.sp, color = theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            length(ep.duration)?.let {
                                Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = theme.inkSoft,
                                    modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(50)).background(theme.inkMute.copy(alpha = 0.1f)).padding(horizontal = 8.dp, vertical = 2.dp))
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Cover(ep.image.ifEmpty { null }, "podcasts", serverUrl, 56.dp)
                    }
                }
                if (episodes.size > shown) TextButton(onClick = { shown += 10 }) { Text(L("suggest.episodes_more"), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = theme.inkSoft) }
                Text(L("suggest.episodes_hint"), fontSize = 11.sp, color = theme.inkMute, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** "33 min", "1 h 5 min". */
private fun length(seconds: Int?): String? {
    if (seconds == null || seconds <= 0) return null
    val minutes = (seconds + 30) / 60
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

/**
 * The entry the conversation is held from (asked about from Carnet): its cover
 * and title, opening it in Carnet — or, where Carnet is not, its page in the garden.
 */
@Composable
internal fun HeldEntryRow(entry: ConversationEntry, serverUrl: String) {
    val theme = LocalMauriceTheme.current
    val uri = LocalUriHandler.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button) {
            val inCarnet = runCatching { uri.openUri("carnet://entry/${entry.ref}") }.isSuccess
            if (!inCarnet) entry.webPath?.let { runCatching { uri.openUri(it) } }
        }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(entry.image, entry.collection, serverUrl, 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = theme.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(L("suggest.kind.${entry.collection}"), fontSize = 11.sp, color = theme.inkSoft)
        }
        Spacer(Modifier.width(4.dp))
        OpenMark()
    }
}

/**
 * The ↗ of a row that opens its entry elsewhere (Carnet, or the garden)
 * (`OpenMark`, iOS 07d3935). The whole row is the button; the mark, round
 * and in ink, says so — a bare small arrow read as a target too small to hit.
 * Drawn only: the row carries the action and what it says.
 */
@Composable
internal fun OpenMark() {
    val theme = LocalMauriceTheme.current
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(theme.surfaceAlt).border(1.dp, theme.rule, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(MauriceIcons.NorthEast, null, tint = theme.ink, modifier = Modifier.size(14.dp))
    }
}
