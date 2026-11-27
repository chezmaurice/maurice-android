package eu.chezmaurice.carnet.shelf

import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.carnet.ui.kindLabel
import eu.chezmaurice.carnet.ui.kindLine
import eu.chezmaurice.carnet.ui.noteCount
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.carnet.CarnetRouter
import eu.chezmaurice.carnet.Navigator
import eu.chezmaurice.carnet.Route
import eu.chezmaurice.carnet.entry.deleteWarning
import eu.chezmaurice.carnet.platform.Offline
import eu.chezmaurice.carnet.platform.offlineKey
import eu.chezmaurice.carnet.ui.SearchField
import eu.chezmaurice.carnet.ui.matchesAll
import eu.chezmaurice.carnet.ui.AvatarStack
import eu.chezmaurice.carnet.ui.CoverImage
import eu.chezmaurice.carnet.ui.GridBar
import eu.chezmaurice.carnet.ui.GridButton
import eu.chezmaurice.carnet.ui.GridIconButton
import eu.chezmaurice.carnet.ui.GridLabel
import eu.chezmaurice.carnet.ui.GridLoading
import eu.chezmaurice.carnet.ui.GridMessage
import eu.chezmaurice.carnet.ui.GridRule
import eu.chezmaurice.carnet.ui.KindChip
import eu.chezmaurice.carnet.ui.LocalApi
import eu.chezmaurice.carnet.ui.MemberBadge
import eu.chezmaurice.ui.accessibilityLabel
import eu.chezmaurice.carnet.ui.PAGE
import eu.chezmaurice.carnet.ui.StateMark
import eu.chezmaurice.core.carnet.GardenApi
import eu.chezmaurice.core.carnet.KindCount
import eu.chezmaurice.core.carnet.Shelf
import eu.chezmaurice.core.carnet.ShelfEntry
import eu.chezmaurice.core.carnet.ShelfKind
import eu.chezmaurice.core.carnet.ShelfSource
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.maurice.absoluteUrl
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.wordmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Sort(val label: String) { Activity("carnet.shelf.sort.activity"), Title("carnet.shelf.sort.title") }

/** What the shelf remembers between launches: the ticked kinds and the sort. */
private object ShelfPrefs {
    fun read(): Pair<Set<ShelfKind>, Sort> {
        val raw = Offline.read("shelf-prefs").orEmpty().split("|")
        val kinds = raw.getOrNull(0).orEmpty().split(",").mapNotNull { ShelfKind.of(it) }.toSet()
        val sort = Sort.entries.firstOrNull { it.name == raw.getOrNull(1) } ?: Sort.Activity
        return kinds to sort
    }
    fun write(kinds: Set<ShelfKind>, sort: Sort) =
        Offline.write("shelf-prefs", ShelfKind.entries.filter { it in kinds }.joinToString(",") { it.wire } + "|" + sort.name)
}

/**
 * Carnet's home (`ShelfView`): every entry of every kind, newest touched
 * first; a filter by kind; search; a "Continue" bar for the book under way;
 * swipe right to archive, left to delete; the last list kept for offline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(session: Session, nav: Navigator) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val api = LocalApi.current ?: return
    val garden = remember(api) { GardenApi(api) }
    val scope = rememberCoroutineScope()
    val cacheKey = remember(nav) { offlineKey(nav.scope, "shelf") }
    var shelf by remember { mutableStateOf<Shelf?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }
    var archived by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var identity by remember { mutableStateOf(false) }
    // The « + »: a new entry in the member's own words, or a book from a file.
    var adding by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ShelfEntry?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val initial = remember { ShelfPrefs.read() }
    var ticked by remember { mutableStateOf(initial.first) }
    var sort by remember { mutableStateOf(initial.second) }
    val state by session.state.collectAsState()
    val member = state.activeMember
    val household = state.current

    suspend fun fetch() {
        val asked = archived
        try {
            val (fresh, text) = garden.shelf(asked)
            if (asked != archived) return
            shelf = fresh
            nav.username = fresh.garden.username; nav.site = fresh.garden.site
            if (!asked) Offline.write(cacheKey, text)
            offline = false; error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val cached = if (!archived) Offline.read(cacheKey)?.let { runCatching { Shelf.parse(it) }.getOrNull() } else null
            if (cached != null) { shelf = cached; offline = true; error = null }
            else if (shelf?.entries.isNullOrEmpty()) error = strings("carnet.shelf.load_failed", e.message.orEmpty())
        }
        loading = false
    }

    // The last list at once, then the server's.
    LaunchedEffect(archived) {
        if (!archived && shelf == null) Offline.read(cacheKey)?.let { runCatching { Shelf.parse(it) }.getOrNull() }?.let { shelf = it; offline = true; loading = false }
        if (archived) { shelf = null; loading = true }
        fetch()
    }
    LaunchedEffect(Unit) { runCatching { session.refreshMembers() } }
    // Back on the shelf from a page, or something shared reached the household: read it again.
    var away by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(nav.shelfVersion) }
    LaunchedEffect(nav.stack.isEmpty(), nav.shelfVersion) {
        if (nav.stack.isNotEmpty()) { away = true; return@LaunchedEffect }
        if (away || version != nav.shelfVersion) { away = false; version = nav.shelfVersion; fetch() }
    }
    // An entry another app asked for: in the list, or fetched (it may be minutes old).
    val wanted by CarnetRouter.entry.collectAsState()
    LaunchedEffect(wanted) {
        val ref = wanted ?: return@LaunchedEffect
        // From a notification: an entry no longer there leaves the member on the shelf, not on the page they were reading.
        val orShelf = CarnetRouter.takeEntry()
        val parts = ref.split("/")
        var found: ShelfEntry? = null
        fun find() = shelf?.entries?.firstOrNull { it.id == ref || it.translations.any { t -> t.id == ref } }
        // A book just added to the library (`calibre/<id>`) has no page of its
        // own to ask for: it is in the list once the list is read again.
        if (ref.startsWith("calibre/")) {
            found = find()
            for (wait in listOf(0L, 1000L, 2500L)) {
                if (found != null) break
                delay(wait); fetch(); found = find()
            }
            found?.let { nav.reset(Route.Entry(it)) }
            return@LaunchedEffect
        }
        // A share just made is in no list read before it: read it again first,
        // and take another member's entry over one of the member's own of the same name.
        if (orShelf) { fetch(); found = shelf?.entries?.firstOrNull { it.isForeign && it.id == ref } }
        for (wait in listOf(0L, 700L, 1500L, 3000L)) {
            if (found != null) break
            delay(wait)
            found = shelf?.entries?.firstOrNull { it.id == ref || it.translations.any { t -> t.id == ref } }
                ?: if (parts.size == 3) runCatching { garden.entry(parts[0], parts[1], parts[2]).entry }.getOrNull() else null
            if (found != null) break
        }
        val entry = found
        if (entry != null) { nav.reset(Route.Entry(entry)); fetch() } else if (orShelf) nav.reset()
    }
    // A conversation Maurice opened, from a notification.
    val conversation by CarnetRouter.conversation.collectAsState()
    LaunchedEffect(conversation) {
        val (tag, id) = conversation ?: return@LaunchedEffect
        if (tag != null && tag != household?.id) return@LaunchedEffect
        CarnetRouter.takeConversation()
        nav.reset(Route.Maurice, Route.Conversation(id))
    }

    val entries = shelf?.entries.orEmpty()
    val visible = entries.filter { e ->
        (ticked.isEmpty() || e.kind in ticked) && matchesAll(query, e.title + "\n" + e.byline.orEmpty())
    }.let { if (sort == Sort.Title) it.sortedBy { e -> e.title.lowercase() } else it }
    val reading = entries.filter { it.source?.progress?.finished == false }.maxByOrNull { it.source?.progress?.updatedAt.orEmpty() }

    fun archive(entry: ShelfEntry) {
        val on = !archived
        shelf = shelf?.copy(entries = entries.filterNot { it.id == entry.id })
        scope.launch {
            try { garden.setArchived(entry, on) } catch (e: CancellationException) { throw e } catch (e: Throwable) { actionError = e.message }
            fetch()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // The chrome: the member (and the menu), search, sort.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Box(Modifier.size(42.dp).clip(CircleShape).clickable { menu = true }.accessibilityLabel(L("carnet.menu")), contentAlignment = Alignment.Center) {
                    if (member != null && household != null) MemberBadge(member.displayName, member.avatarColor, absoluteUrl(household.serverUrl, member.avatarUrl), 30.dp)
                }
                ShelfMenu(menu, onDismiss = { menu = false }, member = member, householdName = household?.name.orEmpty(), serverUrl = household?.serverUrl.orEmpty(),
                    count = if (entries.isEmpty() || archived) null else entries.size, archived = archived,
                    onIdentity = { identity = true }, onEntries = { archived = false }, onArchived = { archived = !archived },
                    onNotes = { nav.push(Route.Notes) }, onMaurice = { nav.push(Route.Maurice) })
            }
            Spacer(Modifier.weight(1f))
            GridIconButton(Icons.Default.Search, L("carnet.search"), on = searching) { searching = !searching; if (!searching) query = "" }
            Spacer(Modifier.width(8.dp))
            Box {
                var sortOpen by remember { mutableStateOf(false) }
                GridIconButton(DomainGlyphs.of("arrow.up.arrow.down"), L("carnet.sort"), on = sort != Sort.Activity) { sortOpen = true }
                DropdownMenu(sortOpen, { sortOpen = false }) {
                    for (s in Sort.entries) DropdownMenuItem(
                        text = { Text(if (s == Sort.Activity) L("carnet.shelf.sort.default", L(s.label)) else L(s.label), fontWeight = if (s == sort) FontWeight.SemiBold else FontWeight.Normal) },
                        onClick = { sortOpen = false; sort = s; ShelfPrefs.write(ticked, s) },
                    )
                }
            }
        }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; scope.launch { fetch(); refreshing = false } }, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "head") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = PAGE).padding(top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val kinds = if (ticked.isEmpty()) L("carnet.shelf.everything") else ShelfKind.entries.filter { it in ticked }.map { kindLabel(it) }.joinToString(", ")
                            val scopeLabel = if (archived) (if (ticked.isEmpty()) L("carnet.shelf.archived") else L("carnet.shelf.archived_kinds", kinds)) else kinds
                            GridLabel(if (loading) scopeLabel else "${if (offline) L("carnet.offline") + " · " else ""}$scopeLabel · ${visible.size}", modifier = Modifier.weight(1f))
                            if (archived) GridLabel(L("carnet.shelf.back_to_list"), color = theme.ink, underline = true, modifier = Modifier.clickable { archived = false })
                            else if (ticked.isNotEmpty()) GridLabel(L("give.show_all"), color = theme.ink, underline = true, modifier = Modifier.clickable { ticked = emptySet(); ShelfPrefs.write(ticked, sort) })
                        }
                        Text("Carnet", style = wordmark(32.sp), color = theme.ink)
                    }
                }
                if (searching) item(key = "search") { SearchField(query, L("link_book.search")) { query = it } }
                item(key = "rule") { GridRule() }
                when {
                    loading && entries.isEmpty() -> item { GridLoading(Modifier.heightIn(min = 240.dp)) }
                    error != null -> item { GridMessage(error!!) }
                    visible.isEmpty() -> item {
                        Text(
                            if (entries.isNotEmpty()) L("carnet.nothing_matches") else if (archived) L("carnet.shelf.nothing_archived") else L("carnet.nothing_here"),
                            fontSize = 11.5.sp, color = theme.inkHint, modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                    else -> items(visible, key = { it.id }) { entry ->
                        SwipeRow(
                            archiveLabel = if (archived) L("entry.unarchive") else L("entry.archive"),
                            canDelete = !entry.isLibraryOnly && !entry.isForeign,
                            onArchive = { archive(entry) }, onDelete = { pendingDelete = entry },
                        ) { ShelfRow(entry, Modifier.clickable { nav.push(Route.Entry(entry)) }) }
                    }
                }
            }
            if (!searching) AddEntryButton(Modifier.align(Alignment.BottomEnd).padding(end = PAGE, bottom = 16.dp)) { adding = true }
        }
        // The footer: the book under way, and the kinds.
        Column(Modifier.fillMaxWidth().background(theme.surface).navigationBarsPadding().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val bookId = reading?.source?.bookId
            if (reading != null && bookId != null) Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(reading, 28.dp, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    GridLabel(L("carnet.shelf.continue", reading.source?.progress?.chapterTitle.orEmpty()), 9.5.sp)
                    Text(reading.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                GridButton(L("carnet.reading.resume"), symbol = "book", primary = true, small = true) { nav.push(Route.Resume(bookId, reading.title, reading.byline)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in shelf?.kinds.orEmpty()) {
                    val kind = k.kind ?: continue
                    KindChip(kindLabel(kind), k.count, kind in ticked) {
                        ticked = if (kind in ticked) ticked - kind else ticked + kind
                        ShelfPrefs.write(ticked, sort)
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(L("entry.delete.confirm", entry.title)) },
            text = { Text(deleteWarning(entry, strings)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    shelf = shelf?.copy(entries = entries.filterNot { it.id == entry.id })
                    scope.launch {
                        try { garden.delete(entry) } catch (e: CancellationException) { throw e } catch (e: Throwable) { actionError = e.message }
                        fetch()
                    }
                }) { Text(L("entry.delete"), color = Color(0xFFC0392B)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(L("common.cancel"), color = theme.ink) } },
            containerColor = theme.surface,
        )
    }
    actionError?.let {
        AlertDialog(
            onDismissRequest = { actionError = null }, title = { Text(L("entry.action_failed")) }, text = { Text(it) },
            confirmButton = { TextButton(onClick = { actionError = null }) { Text(L("common.ok"), color = theme.ink) } }, containerColor = theme.surface,
        )
    }
    if (identity) IdentitySheet(session) { identity = false }
    // A row kept opens its entry, as a `carnet://entry/…` link does.
    if (adding) AddEntrySheet(api, onOpen = CarnetRouter::openEntry) { adding = false }
}

/** The member's menu: who is here (and the way to another), the shelf's views. */
@Composable
private fun ShelfMenu(
    open: Boolean, onDismiss: () -> Unit, member: eu.chezmaurice.core.model.Member?, householdName: String, serverUrl: String,
    count: Int?, archived: Boolean, onIdentity: () -> Unit, onEntries: () -> Unit, onArchived: () -> Unit, onNotes: () -> Unit, onMaurice: () -> Unit,
) {
    val theme = LocalMauriceTheme.current
    DropdownMenu(open, onDismiss, modifier = Modifier.width(250.dp), containerColor = theme.surface) {
        Row(Modifier.fillMaxWidth().clickable { onDismiss(); onIdentity() }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (member != null) MemberBadge(member.displayName, member.avatarColor, absoluteUrl(serverUrl, member.avatarUrl), 36.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(member?.displayName ?: L("picker.admin.submit"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = theme.ink)
                GridLabel(householdName, 9.sp)
            }
            Text("⌄", color = theme.inkMute)
        }
        GridRule(modifier = Modifier.padding(horizontal = 14.dp))
        MenuRow(L("carnet.shelf.entries"), "doc.text", count?.toString(), current = !archived) { onDismiss(); onEntries() }
        MenuRow(L("carnet.shelf.archived"), "tray.full", null, current = archived) { onDismiss(); onArchived() }
        MenuRow(L("carnet.shelf.notes"), "note.text", null, current = false) { onDismiss(); onNotes() }
        MenuRow("Maurice", "bubble.left", null, current = false) { onDismiss(); onMaurice() }
    }
}

@Composable
private fun MenuRow(title: String, symbol: String, count: String?, current: Boolean, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Row(
        Modifier.padding(horizontal = 6.dp).padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (current) theme.rule else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(DomainGlyphs.of(symbol), null, tint = theme.ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 15.sp, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, color = theme.ink, modifier = Modifier.weight(1f))
        if (count != null) GridLabel(count, 9.5.sp)
    }
}

/** Swipe right to archive (or unarchive), left to delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(archiveLabel: String, canDelete: Boolean, onArchive: () -> Unit, onDelete: () -> Unit, content: @Composable () -> Unit) {
    val theme = LocalMauriceTheme.current
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> onArchive()
            SwipeToDismissBoxValue.EndToStart -> onDelete()
            else -> {}
        }
        false // The row stays; the list moves.
    })
    SwipeToDismissBox(
        state, enableDismissFromEndToStart = canDelete,
        backgroundContent = {
            val toStart = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val tint = if (toStart) theme.ink else Color(0xFFC0392B)
            Row(
                Modifier.fillMaxSize().background(tint.copy(alpha = 0.8f)).padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (toStart) Arrangement.Start else Arrangement.End,
            ) { Text(if (toStart) archiveLabel else L("entry.delete"), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (toStart) theme.bg else Color.White) }
        },
    ) { Box(Modifier.background(theme.bg)) { content() } }
}

/** One entry: its cover, title, kind and byline, its state, its reading. */
@Composable
fun ShelfRow(entry: ShelfEntry, modifier: Modifier = Modifier) {
    val theme = LocalMauriceTheme.current
    Row(modifier.fillMaxWidth().padding(horizontal = PAGE), verticalAlignment = Alignment.CenterVertically) {
        CoverImage(entry, 52.dp, if (entry.kind.squareCover) 52.dp else 74.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).heightIn(min = 102.dp)) {
            Row(Modifier.weight(1f, fill = false).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = theme.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(kindLine(entry), fontSize = 13.sp, color = theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.padding(top = 5.dp)) { StateLine(entry) }
                }
                Spacer(Modifier.width(12.dp))
                SourceColumn(entry.source)
            }
            GridRule()
        }
    }
}

@Composable
fun StateLine(entry: ShelfEntry) {
    val theme = LocalMauriceTheme.current
    val api = LocalApi.current
    // A copy someone gave, not yet written on: who gave it says more than « Not opened ».
    val from = entry.source?.sharedBy
    if (from != null && entry.isUntouched) return GridLabel(L("article.shared_by", from.displayName.ifEmpty { "@" + from.username }), 9.5.sp, theme.inkSoft)
    if (entry.isUntouched && !entry.isShared) return GridLabel(L("carnet.shelf.not_opened"), 9.5.sp, theme.inkHint)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        if (entry.isShared && api != null) AvatarStack(entry.people, api.baseUrl, 16.dp)
        if (entry.noteCount > 0) GridLabel(noteCount(entry.noteCount), 9.5.sp)
        if (entry.noteCount > 0 && entry.shared != null) GridLabel("·", 9.5.sp)
        entry.shared?.let { StateMark(it.published) }
    }
}

@Composable
private fun SourceColumn(source: ShelfSource?) {
    val theme = LocalMauriceTheme.current
    source ?: return
    when {
        source.isBook -> Column(Modifier.width(52.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val p = source.progress
            GridLabel(if (p == null) L("carnet.reading.unread") else if (p.finished) L("carnet.reading.done") else "${kotlin.math.round(p.fraction * 100).toInt()} %", 9.5.sp, if (p == null) theme.inkMute else theme.ink)
            GridBar(p?.fraction ?: 0.0, width = 44.dp, accentWhenFull = false)
        }
        source.captured == true -> GridLabel(L("carnet.minutes", source.readingMinutes ?: 1), 9.5.sp, theme.ink)
        else -> GridLabel(L("carnet.shelf.link_only"), 9.5.sp)
    }
}
