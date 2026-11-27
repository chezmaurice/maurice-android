package eu.chezmaurice.carnet.reading

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.carnet.ui.GridButton
import eu.chezmaurice.carnet.ui.GridLabel
import eu.chezmaurice.carnet.ui.GridRule
import eu.chezmaurice.carnet.ui.GridSheet
import eu.chezmaurice.carnet.ui.LocalApi
import eu.chezmaurice.core.carnet.EntryDetail
import eu.chezmaurice.core.carnet.LinkTarget
import eu.chezmaurice.core.carnet.ResonanceSource
import eu.chezmaurice.core.carnet.ResonancesApi
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Following a [[wiki-link]] of a note or a fiche: opens the entry it names
 * in Carnet, published or not. Provided by the app, with its navigation.
 */
val LocalOpenWikiLink = staticCompositionLocalOf<((EntryDetail.Link) -> Unit)?> { null }

/** The glyph of a collection, for a target's row. */
private fun collectionSymbol(collection: String) = when (collection) {
    "movies" -> "film"; "series" -> "tv"; "books" -> "book"; "music" -> "music.note"; "podcasts" -> "mic"
    "games" -> "gamecontroller"; "people" -> "person"; "articles" -> "doc.text"; else -> "leaf"
}

/**
 * The entry a résonance goes to (`LinkTargetPicker`): the garden searched as
 * the member types, a quarter of a second after the last key.
 */
@Composable
fun LinkTargetPicker(onDismiss: () -> Unit, onPick: (LinkTarget) -> Unit) {
    val theme = LocalMauriceTheme.current
    val api = LocalApi.current?.let { remember(it) { ResonancesApi(it) } } ?: return
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LinkTarget>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        delay(250)
        searching = true
        runCatching { api.targets(query) }.onSuccess { results = it }
        searching = false
    }
    GridSheet(onDismiss) {
        GridLabel(L("resonance.pick"))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).border(0.5.dp, theme.ruleHard, RoundedCornerShape(10.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text(L("resonance.search"), fontSize = 15.sp, color = theme.inkMute)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = theme.ink), cursorBrush = SolidColor(theme.ink), modifier = Modifier.fillMaxWidth())
            }
            if (searching) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = theme.inkMute)
        }
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            if (!searching && results.isEmpty()) Text(L("resonance.none"), fontSize = 13.sp, color = theme.inkMute, modifier = Modifier.padding(vertical = 12.dp))
            for (t in results) {
                Row(Modifier.fillMaxWidth().clickable { onPick(t); onDismiss() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(DomainGlyphs.of(collectionSymbol(t.collection)), null, tint = theme.inkSoft, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        GridLabel("${L("suggest.kind.${t.collection}").takeIf { !it.startsWith("suggest.") } ?: t.collection} · [[${t.linkBasename}]]", 9.sp)
                    }
                }
                GridRule()
            }
        }
    }
}

/** "Link to…", or the entry picked, with ✕ to drop it. */
@Composable
fun LinkTargetField(target: LinkTarget?, onOpen: () -> Unit, onClear: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(0.5.dp, theme.ruleHard, RoundedCornerShape(10.dp)).clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(DomainGlyphs.of("leaf"), null, tint = theme.ink, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        if (target != null) {
            Text(target.title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.dp))
            GridLabel("[[${target.linkBasename}]]", 8.5.sp, modifier = Modifier.weight(1f))
            Text("✕", fontSize = 12.sp, color = theme.inkMute, modifier = Modifier.clickable(onClick = onClear).padding(start = 8.dp))
        } else {
            Text(L("resonance.link_to"), fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = theme.inkSoft, modifier = Modifier.weight(1f))
            Text("›", fontSize = 14.sp, color = theme.inkMute)
        }
    }
}

/**
 * The full gesture from a book's or an article's page (`ResonanceSheet`):
 * pick the entry this feeds, write the thought, send. It lands on that
 * entry's fiche with a link back to [source].
 */
@Composable
fun ResonanceSheet(source: ResonanceSource, onDismiss: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val api = LocalApi.current?.let { remember(it) { ResonancesApi(it) } } ?: return
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<LinkTarget?>(null) }
    var note by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    GridSheet(onDismiss) {
        GridLabel(L("resonance.title"))
        Text(source.label, fontSize = 13.sp, color = theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        LinkTargetField(target, onOpen = { picking = true }, onClear = { target = null })
        GridLabel(L("resonance.note"), 9.sp)
        BasicTextField(
            note, { note = it }, textStyle = TextStyle(fontSize = 15.sp, color = theme.ink), cursorBrush = SolidColor(theme.ink),
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp).clip(RoundedCornerShape(10.dp)).background(theme.bg).border(0.5.dp, theme.ruleHard, RoundedCornerShape(10.dp)).padding(10.dp),
        )
        if (failed) Text(L("suggest.failed"), fontSize = 12.sp, color = androidx.compose.ui.graphics.Color(0xFFC0392B))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            GridButton(L("resonance.cancel"), small = true, onClick = onDismiss)
            Spacer(Modifier.width(8.dp))
            GridButton(L("resonance.send"), primary = true, small = true, enabled = target != null && note.isNotBlank() && !sending) {
                val t = target ?: return@GridButton
                sending = true; failed = false
                scope.launch {
                    try { api.create(t, note.trim(), source = source); onDismiss() }
                    catch (e: CancellationException) { throw e } catch (_: Throwable) { failed = true }
                    sending = false
                }
            }
        }
    }
    if (picking) LinkTargetPicker(onDismiss = { picking = false }) { target = it }
}
