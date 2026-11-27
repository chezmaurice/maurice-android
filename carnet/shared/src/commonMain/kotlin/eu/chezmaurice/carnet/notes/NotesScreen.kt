package eu.chezmaurice.carnet.notes

import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.maurice.budget.monthName
import eu.chezmaurice.maurice.consent.LocalAppLanguage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.carnet.Navigator
import eu.chezmaurice.carnet.Route
import eu.chezmaurice.carnet.entry.MineItemView
import eu.chezmaurice.carnet.entry.NoteActions
import eu.chezmaurice.carnet.entry.NoteEditTarget
import eu.chezmaurice.carnet.entry.NoteEditing
import eu.chezmaurice.carnet.ui.CoverImage
import eu.chezmaurice.carnet.ui.GridIconButton
import eu.chezmaurice.carnet.ui.GridLabel
import eu.chezmaurice.carnet.ui.GridLoading
import eu.chezmaurice.carnet.ui.GridMessage
import eu.chezmaurice.carnet.ui.GridRule
import eu.chezmaurice.carnet.ui.GridTitle
import eu.chezmaurice.carnet.ui.LocalApi
import eu.chezmaurice.carnet.ui.PAGE
import eu.chezmaurice.carnet.ui.SearchField
import eu.chezmaurice.carnet.ui.matchesAll
import eu.chezmaurice.core.carnet.FeedNote
import eu.chezmaurice.core.carnet.GardenApi
import eu.chezmaurice.core.carnet.ShelfEntry
import eu.chezmaurice.ui.LocalMauriceTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** `2026-10` → "October 2026" in [language] (as iOS's "LLLL yyyy"); anything else is [undated]. */
internal fun monthLabel(key: String, language: String, undated: String): String {
    val m = Regex("""^(\d{4})-(\d{2})$""").find(key) ?: return undated
    val month = m.groupValues[2].toInt()
    return if (month in 1..12) "${monthName(month - 1, language)} ${m.groupValues[1]}" else undated
}

/** The notes by month, in the order they came. */
internal fun byMonth(notes: List<FeedNote>): List<Pair<String, List<FeedNote>>> {
    val out = mutableListOf<Pair<String, MutableList<FeedNote>>>()
    for (note in notes) {
        val key = note.item.date.orEmpty().take(7)
        if (out.lastOrNull()?.first == key) out.last().second.add(note) else out.add(key to mutableListOf(note))
    }
    return out
}

/**
 * Every note the member wrote, whatever the entry, newest first, by month
 * (`NotesView`): a note remembered for itself, when the entry it sits under
 * is long forgotten. Each names its entry in small; a tap opens it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(nav: Navigator) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val api = LocalApi.current ?: return
    val garden = remember(api) { GardenApi(api) }
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf<List<FeedNote>>(emptyList()) }
    var entries by remember { mutableStateOf<Map<String, ShelfEntry>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // A note opened to be rewritten, or to be taken off.
    var editing by remember { mutableStateOf<NoteEditTarget?>(null) }
    var deleting by remember { mutableStateOf<NoteEditTarget?>(null) }

    suspend fun fetch() {
        try {
            val feed = garden.notes()
            entries = feed.entries.associateBy { it.id }
            notes = feed.notes
            error = null
        } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            if (notes.isEmpty()) error = strings("carnet.notes.load_failed", e.message.orEmpty())
        }
        loading = false
    }
    // Coming back from an entry: a note may have been added there.
    LaunchedEffect(nav.stack.size) { if (nav.stack.lastOrNull() == Route.Notes) fetch() }

    val visible = notes.filter { note ->
        val entry = entries[note.entryId]
        matchesAll(query, listOfNotNull(note.item.text, note.item.quote, note.item.from?.label, entry?.title, entry?.byline).joinToString("\n"))
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GridIconButton(Icons.AutoMirrored.Filled.ArrowBack, L("carnet.back")) { nav.pop() }
            Spacer(Modifier.weight(1f))
            GridIconButton(Icons.Default.Search, L("carnet.search"), on = searching) { searching = !searching; if (!searching) query = "" }
        }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; scope.launch { fetch(); refreshing = false } }, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "title") { GridTitle(L("carnet.shelf.notes"), if (loading) L("carnet.notes.kicker") else L("carnet.notes.kicker_count", visible.size)) }
                if (searching) item(key = "search") { SearchField(query, L("carnet.notes.search")) { query = it } }
                when {
                    loading -> item(key = "loading") { GridLoading(Modifier.padding(top = 80.dp)) }
                    error != null -> item(key = "error") { GridMessage(error!!) }
                    visible.isEmpty() -> item(key = "empty") {
                        GridRule()
                        Text(
                            if (notes.isEmpty()) L("carnet.notes.empty") else L("carnet.nothing_matches"),
                            fontSize = 13.sp, color = theme.inkMute, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE, vertical = 40.dp),
                        )
                    }
                    else -> for ((key, month) in byMonth(visible)) {
                        item(key = "month-$key") {
                            Column {
                                GridLabel(monthLabel(key, LocalAppLanguage.current, L("carnet.notes.undated")), 9.5.sp, modifier = Modifier.padding(horizontal = PAGE).padding(top = 18.dp, bottom = 8.dp))
                                GridRule()
                            }
                        }
                        for (note in month) item(key = note.id) {
                            Column {
                                NoteRow(note, entries[note.entryId], onEdit = { delete ->
                                    entries[note.entryId]?.let { e -> if (delete) deleting = NoteEditTarget(e, note.item) else editing = NoteEditTarget(e, note.item) }
                                }) { entries[note.entryId]?.let { nav.push(Route.Entry(it)) } }
                                GridRule()
                            }
                        }
                    }
                }
            }
        }
    }
    NoteEditing(garden, editing, deleting, onEditingDone = { editing = null }, onDeletingDone = { deleting = null }) { fetch() }
}

/**
 * One note as its entry's page shows it, and under it, in small, the entry.
 * A note the member may rewrite or take off is a target of its own (tap,
 * long press), its entry's line the way to the entry; otherwise the whole
 * row opens the entry.
 */
@Composable
private fun NoteRow(note: FeedNote, entry: ShelfEntry?, onEdit: (Boolean) -> Unit, onOpen: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val editable = entry != null && (note.item.canEdit || note.item.canDelete)
    Column(
        Modifier.fillMaxWidth().clickable(enabled = entry != null && !editable, onClick = onOpen).padding(horizontal = PAGE, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (editable) NoteActions(note.item, onEdit = { onEdit(false) }, onDelete = { onEdit(true) }) { MineItemView(note.item, signed = false) }
        else MineItemView(note.item, signed = false)
        if (entry != null) Row(
            Modifier.clickable(enabled = editable, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.foundation.layout.Box(Modifier.clip(RoundedCornerShape(2.dp))) {
                CoverImage(entry, 16.dp, if (entry.kind.squareCover) 16.dp else 22.dp)
            }
            Text(entry.title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            GridLabel(L("suggest.kind.${entry.kind.wire}"), 9.sp)
            Spacer(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = theme.inkMute, modifier = Modifier.size(14.dp))
        }
    }
}
