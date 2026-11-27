package eu.chezmaurice.carnet.entry

import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.ui.Strings
import eu.chezmaurice.carnet.ui.kindLine
import eu.chezmaurice.carnet.ui.noteCount
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.carnet.Navigator
import eu.chezmaurice.carnet.Route
import eu.chezmaurice.carnet.platform.rememberMauriceApp
import eu.chezmaurice.carnet.ui.AvatarStack
import eu.chezmaurice.carnet.ui.Back
import eu.chezmaurice.carnet.ui.CoverImage
import eu.chezmaurice.carnet.ui.Face
import eu.chezmaurice.carnet.ui.FoldedCorner
import eu.chezmaurice.carnet.ui.GridBar
import eu.chezmaurice.carnet.ui.GridButton
import eu.chezmaurice.carnet.ui.GridIconButton
import eu.chezmaurice.ui.accessibilityLabel
import eu.chezmaurice.ui.accessibilityName
import eu.chezmaurice.carnet.ui.GridLabel
import eu.chezmaurice.carnet.ui.LocalApi
import eu.chezmaurice.carnet.ui.MarkdownBlocks
import eu.chezmaurice.carnet.ui.SheetShape
import eu.chezmaurice.carnet.ui.StateMark
import eu.chezmaurice.carnet.ui.minePaper
import eu.chezmaurice.carnet.ui.paper
import eu.chezmaurice.core.carnet.DeployState
import eu.chezmaurice.core.carnet.EntryConversation
import eu.chezmaurice.core.carnet.EntryDetail
import eu.chezmaurice.core.carnet.EntryPage
import eu.chezmaurice.core.carnet.GardenApi
import eu.chezmaurice.core.carnet.MineItem
import eu.chezmaurice.core.carnet.ShelfEntry
import eu.chezmaurice.core.carnet.ShelfKind
import eu.chezmaurice.core.carnet.ShelfSource
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.maurice.platform.plainTextClip
import eu.chezmaurice.ui.BoaterHat
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.wordmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One entry (`EntryView`): its identity; for a book or a captured article,
 * the way to read it; for an article, its summary; then the sheet with two
 * faces — mine (notes, highlights, what came of conversations with Maurice)
 * and the shared one (what one would say of it to someone else, a draft until
 * published on the member's site) — turned over by its folded corner.
 */
@Composable
fun EntryScreen(session: Session, nav: Navigator, initial: ShelfEntry, asFiche: Boolean = false) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val api = LocalApi.current ?: return
    val garden = remember(api) { GardenApi(api) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val maurice = rememberMauriceApp()
    var entry by remember { mutableStateOf(initial) }
    var detail by remember { mutableStateOf<EntryDetail?>(null) }
    var conversations by remember { mutableStateOf<List<EntryConversation>>(emptyList()) }
    var loading by remember { mutableStateOf(!initial.isLibraryOnly) }
    var error by remember { mutableStateOf<String?>(null) }
    var face by remember { mutableStateOf(if (initial.mine == null && initial.shared != null) Face.Shared else Face.Mine) }
    val angle = remember { Animatable(0f) }
    // The list carries both summaries: the page opens on them, offline too.
    var summary by remember { mutableStateOf(initial.source?.summary) }
    var summarizing by remember { mutableStateOf(false) }
    var summaryError by remember { mutableStateOf<String?>(null) }
    var summaryOpen by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf(false) }
    // A note of the member's side opened to be rewritten, or to be taken off.
    var noteEditing by remember { mutableStateOf<NoteEditTarget?>(null) }
    var noteDeleting by remember { mutableStateOf<NoteEditTarget?>(null) }
    var editing by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var linkingBook by remember { mutableStateOf(false) }
    var resonating by remember { mutableStateOf(false) }
    // A book names itself by title (the server finds its fiche); an article links back to its own fiche.
    val resonanceSource = when {
        entry.isForeign -> null
        entry.source?.isBook == true -> eu.chezmaurice.core.carnet.ResonanceSource(entry.title, bookTitle = entry.title)
        entry.source?.type == "article" && entry.slug != null -> eu.chezmaurice.core.carnet.ResonanceSource(entry.title, basename = "${entry.slug}-fiche")
        else -> null
    }
    var confirmPublish by remember { mutableStateOf(false) }
    var confirmUnpublish by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // An archive or a delete the server refused.
    var actionError by remember { mutableStateOf<String?>(null) }
    var writeError by remember { mutableStateOf<String?>(null) }
    var deploy by remember { mutableStateOf<DeployState?>(null) }
    var lastPublish by remember { mutableStateOf<Boolean?>(null) }
    var landed by remember { mutableStateOf(false) }
    val articlePage = entry.kind == ShelfKind.Articles && !asFiche

    fun watchDeploy() = scope.launch {
        while (deploy?.inProgress == true) {
            delay(2_000)
            runCatching { garden.deployState() }.getOrNull()?.let { deploy = it }
        }
        if (lastPublish == null || deploy?.failed == true) return@launch
        landed = true
        delay(4_000)
        landed = false
    }
    fun apply(page: EntryPage) {
        entry = page.entry; detail = page.detail; loading = false; error = null
        page.deploy?.let { deploy = it; watchDeploy() }
    }
    suspend fun summarize(force: Boolean = false) {
        val slug = entry.slug ?: return
        val locale = entry.locale ?: return
        if (entry.isForeign || entry.source?.captured != true || summarizing) return
        summarizing = true; summaryError = null
        try { summary = garden.summaries(slug, locale, force) } catch (e: CancellationException) { throw e } catch (e: Throwable) { summaryError = strings("carnet.entry.summary.failed", e.message.orEmpty()) }
        summarizing = false
    }
    // Opened straight from elsewhere (the Maurice app), before the shelf was read: whether the
    // member has a site is learnt from it, which decides whether a draft card may be shared.
    LaunchedEffect(Unit) {
        if (nav.username.isNotEmpty()) return@LaunchedEffect
        runCatching { garden.shelf().first.garden }.getOrNull()?.let { nav.username = it.username; nav.site = it.site }
    }
    LaunchedEffect(initial.id) {
        if (entry.isLibraryOnly) return@LaunchedEffect
        launch { conversations = runCatching { garden.conversations(entry) }.getOrDefault(conversations) }
        try {
            // A page without a summary does not take away the one the list gave.
            garden.entry(entry)?.let { page -> apply(page); if (!summarizing) page.entry.source?.summary?.let { summary = it } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = strings("carnet.entry.load_failed", e.message.orEmpty())
        }
        loading = false
        if (articlePage && summary?.current != true) summarize()
    }
    fun publish(on: Boolean) {
        working = true; writeError = null; landed = false
        scope.launch {
            try { val page = garden.setPublished(entry, on); lastPublish = on; apply(page) } catch (e: CancellationException) { throw e } catch (e: Throwable) { writeError = e.message }
            working = false
        }
    }
    // Archived or deleted, the page has nothing left to show: back to the list,
    // which reads itself again as it reappears. Refused, the page stays and says why.
    fun putAway(action: suspend () -> Unit) {
        working = true
        scope.launch {
            try { action(); nav.pop() } catch (e: CancellationException) { throw e } catch (e: Throwable) { actionError = e.message.orEmpty() }
            working = false
        }
    }
    fun turnOver() = scope.launch {
        angle.animateTo(90f, tween(160))
        face = if (face == Face.Mine) Face.Shared else Face.Mine
        angle.snapTo(-90f)
        angle.animateTo(0f, tween(200))
    }
    val ask = rememberAsk(nav, entry)

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // The chrome: back; sharing; more.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GridIconButton(Icons.AutoMirrored.Filled.ArrowBack, L("carnet.back")) { nav.pop() }
            Spacer(Modifier.weight(1f))
            if (entry.isShared) Box(Modifier.clip(CircleShape).clickable { sharing = true }.accessibilityLabel(L("entry.share.shared_with", entry.people.joinToString(", ") { it.displayName })).padding(6.dp)) { AvatarStack(entry.people.take(3), api.baseUrl, 26.dp) }
            // A thought toward another entry (a résonance), from a book or an article being read.
            resonanceSource?.let { Spacer(Modifier.width(8.dp)); GridIconButton(DomainGlyphs.of("leaf"), L("resonance.title")) { resonating = true } }
            if (!entry.isForeign && !entry.isLibraryOnly) { Spacer(Modifier.width(8.dp)); GridIconButton(DomainGlyphs.of("person.badge.plus"), L("entry.share.button")) { sharing = true } }
            // The card — the shared face, when it is the one shown and says something — through the system's share sheet.
            val card = detail?.shared
            if (!articlePage && face == Face.Shared && cardCanBeShared(card, hasSite = nav.site != null || nav.username.isEmpty() /* not known yet: as if it had one */)) { Spacer(Modifier.width(8.dp)); ShareCardButton(entry, card!!) }
            if (!entry.isForeign) {
                Spacer(Modifier.width(8.dp))
                MoreMenu(session, nav, entry, detail, face, working, onUnpublish = { confirmUnpublish = true }, onLinkBook = { linkingBook = true }, onRefresh = {
                    working = true; writeError = null
                    scope.launch {
                        try { apply(garden.refreshDetails(entry)); if (entry.image == null) writeError = strings("carnet.entry.no_picture") }
                        catch (e: CancellationException) { throw e } catch (e: Throwable) { writeError = e.message }
                        working = false
                    }
                })
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Identity(entry)
            if (entry.translations.isNotEmpty()) Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (t in entry.translations) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    GridLabel(L("carnet.entry.also_in", t.locale), 9.5.sp)
                    t.shared?.let { StateMark(it.published) }
                }
            }
            if (articlePage) {
                entry.source?.let { Band(nav, entry, it, articlePage, ficheCount = entry.noteCount + conversations.size) }
                SummaryCard(entry, loading, summary, summarizing, summaryError, summaryOpen, onToggle = { summaryOpen = !summaryOpen }, onRewrite = { scope.launch { summarize(force = true) } }, onRetry = { scope.launch { summarize() } })
            } else {
                if (!asFiche) entry.source?.let { Band(nav, entry, it, articlePage, 0) }
                Sheet(entry, face, angle.value, loading, error, detail, conversations, onTurn = { turnOver() }) { item, delete -> if (delete) noteDeleting = NoteEditTarget(entry, item) else noteEditing = NoteEditTarget(entry, item) }
            }
            if (!asFiche) PutAway(entry, working, onArchive = { on -> putAway { garden.setArchived(entry, on) } }, onDelete = { confirmDelete = true })
        }
        // The footer: write on the face on show.
        Box(Modifier.fillMaxWidth().background(theme.surface).navigationBarsPadding().padding(horizontal = 16.dp)) {
            if (articlePage || face == Face.Mine) WritePanel(onNote = { composing = true }, onAsk = if (entry.isLibraryOnly || entry.isForeign) null else ask)
            else SharedPanel(entry, detail, deploy, lastPublish, landed, working, writeError, hasSite = nav.site != null,
                onEdit = { editing = true }, onPublish = { confirmPublish = true }, onUnpublish = { confirmUnpublish = true }, onOpen = { uri.openUri(it) })
        }
    }

    if (composing) NoteSheet(entry.title, offersPage = entry.source == null, onDismiss = { composing = false }) { text, quote, page ->
        apply(garden.addNote(entry, text, quote, page))
    }
    NoteEditing(garden, noteEditing, noteDeleting, onEditingDone = { noteEditing = null }, onDeletingDone = { noteDeleting = null }) {
        garden.entry(entry)?.let { apply(it) }
    }
    if (editing) SharedEditor(entry.title, detail?.shared?.title.orEmpty(), detail?.shared?.let { withoutTitle(it.body, it.title) }.orEmpty(), onDismiss = { editing = false }) { title, body ->
        // A card already online is deployed again by the server: an update
        // of the site, not a publish one way or the other.
        lastPublish = null
        apply(garden.writeShared(entry, body, title))
    }
    if (linkingBook) LinkBookSheet(remember(api) { eu.chezmaurice.core.carnet.CalibreApi(api) }, garden, entry, onLinked = { apply(it) }) { linkingBook = false }
    if (resonating) resonanceSource?.let { eu.chezmaurice.carnet.reading.ResonanceSheet(it) { resonating = false } }
    if (sharing) ShareSheet(session, garden, entry, onDismiss = { sharing = false }) { page -> if (page != null) apply(page) else { sharing = false; nav.pop() } }
    if (confirmPublish) AlertDialog(
        onDismissRequest = { confirmPublish = false },
        title = { Text(L("entry.publish.confirm")) },
        text = { Text(L("entry.publish.confirm_hint")) },
        confirmButton = { TextButton(onClick = { confirmPublish = false; publish(true) }) { Text(L("entry.publish"), color = theme.ink) } },
        dismissButton = { TextButton(onClick = { confirmPublish = false }) { Text(L("common.cancel"), color = theme.inkSoft) } },
        containerColor = theme.surface,
    )
    if (confirmUnpublish) AlertDialog(
        onDismissRequest = { confirmUnpublish = false },
        title = { Text(L("entry.unpublish.confirm")) },
        text = { Text(L("entry.unpublish.confirm_hint")) },
        confirmButton = { TextButton(onClick = { confirmUnpublish = false; publish(false) }) { Text(L("entry.menu.unpublish"), color = theme.ink) } },
        dismissButton = { TextButton(onClick = { confirmUnpublish = false }) { Text(L("common.cancel"), color = theme.inkSoft) } },
        containerColor = theme.surface,
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(L("entry.delete.confirm", entry.title)) },
        text = { Text(deleteWarning(entry, strings)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; putAway { garden.delete(entry) } }) { Text(L("entry.delete"), color = androidx.compose.ui.graphics.Color(0xFFC0392B)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(L("common.cancel"), color = theme.ink) } },
        containerColor = theme.surface,
    )
    actionError?.let {
        AlertDialog(
            onDismissRequest = { actionError = null }, title = { Text(L("entry.action_failed")) }, text = { Text(it) },
            confirmButton = { TextButton(onClick = { actionError = null }) { Text(L("common.ok"), color = theme.ink) } }, containerColor = theme.surface,
        )
    }
    if (writeError != null && face == Face.Mine && !articlePage) AlertDialog(
        onDismissRequest = { writeError = null }, title = { Text(L("entry.nothing_to_add")) }, text = { Text(writeError.orEmpty()) },
        confirmButton = { TextButton(onClick = { writeError = null }) { Text(L("common.ok"), color = theme.ink) } }, containerColor = theme.surface,
    )
}

@Composable
private fun Identity(entry: ShelfEntry) {
    val theme = LocalMauriceTheme.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 16.dp), verticalAlignment = Alignment.Top) {
        CoverImage(entry, 68.dp, if (entry.kind.squareCover) 68.dp else 98.dp)
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(entry.title, style = wordmark(24.sp), color = theme.ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Text(kindLine(entry), fontSize = 14.sp, color = theme.inkSoft)
            val meta = listOfNotNull(
                entry.rating?.takeIf { it > 0 }?.let { "★".repeat(minOf(it, 5)) },
                entry.status?.takeIf { it.isNotEmpty() },
                entry.date.takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) GridLabel(meta, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** How to read it: a book's chapter and progress, a captured page's minutes, or only the link. */
@Composable
private fun Band(nav: Navigator, entry: ShelfEntry, source: ShelfSource, articlePage: Boolean, ficheCount: Int) {
    val theme = LocalMauriceTheme.current
    val uri = LocalUriHandler.current
    val shape = RoundedCornerShape(14.dp)
    val slug = entry.slug
    val locale = entry.locale
    val bookId = source.bookId
    when {
        source.isBook && bookId != null -> Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 14.dp).fillMaxWidth().clip(shape).background(theme.surfaceAlt).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val p = source.progress
            Row(verticalAlignment = Alignment.CenterVertically) {
                GridLabel(L("carnet.entry.book", source.chapters ?: 0), 9.5.sp, modifier = Modifier.weight(1f))
                Text(L("carnet.book.chapters") + " ›", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = theme.ink, modifier = Modifier.clickable { nav.push(Route.Book(bookId, entry.title, entry.byline)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(p?.chapterTitle ?: if (p == null) L("carnet.book.not_started") else L("carnet.book.in_progress"), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    GridBar(p?.fraction ?: 0.0, accentWhenFull = false)
                }
                Spacer(Modifier.width(14.dp))
                GridButton(if (p == null) L("carnet.reading.read") else L("carnet.reading.resume"), symbol = "book", primary = true, small = true) { nav.push(Route.Resume(bookId, entry.title, entry.byline)) }
            }
        }
        source.captured == true && slug != null && locale != null -> Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 14.dp).fillMaxWidth().clip(shape).background(theme.surfaceAlt).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GridLabel(L("carnet.entry.captured", source.wordCount ?: 0), 9.5.sp, modifier = Modifier.weight(1f))
                source.url?.let { u -> Text(L("carnet.entry.original") + " ↗", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = theme.ink, modifier = Modifier.clickable { uri.openUri(u) }) }
            }
            // A copy someone gave by sharing it: whose it was.
            source.sharedBy?.let { by ->
                Text(L("article.shared_by", by.displayName.ifEmpty { "@" + by.username }), fontSize = 13.sp, color = theme.inkSoft)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(L("carnet.minutes", source.readingMinutes ?: 1), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = theme.ink, modifier = Modifier.weight(1f))
                if (articlePage) FicheButton(ficheCount) { nav.push(Route.Entry(entry, asFiche = true)) }
                GridButton(L("carnet.reading.read"), symbol = "book", primary = true, small = true) { nav.push(if (entry.owner != null) Route.ReadShared(entry) else Route.Read(slug, locale)) }
            }
        }
        else -> Row(
            Modifier.padding(horizontal = 14.dp).padding(bottom = 14.dp).fillMaxWidth().clip(shape).border(1.dp, theme.ruleHard, shape).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(L("carnet.entry.refused"), fontSize = 13.sp, color = theme.inkSoft, modifier = Modifier.weight(1f))
            source.url?.let { u -> GridButton(L("add.open"), symbol = "arrow.up.forward", small = true) { uri.openUri(u) } }
            if (articlePage) FicheButton(ficheCount) { nav.push(Route.Entry(entry, asFiche = true)) }
        }
    }
}

@Composable
private fun FicheButton(count: Int, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Row(Modifier.height(40.dp).minePaper(theme, 20.dp).clip(CircleShape).clickable(onClick = onClick).accessibilityName(if (count > 0) L("carnet.entry.fiche_items", count) else L("carnet.entry.fiche")).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(DomainGlyphs.of("note.text"), null, tint = theme.ink, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(7.dp))
        Text(if (count > 0) L("carnet.entry.fiche_count", count) else L("carnet.entry.fiche"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = theme.ink)
    }
}

/** An article's summary: the short one, the long one on demand, rewritten by Maurice. */
@Composable
private fun SummaryCard(
    entry: ShelfEntry, loading: Boolean, summary: ShelfSource.Summary?, summarizing: Boolean, error: String?, open: Boolean,
    onToggle: () -> Unit, onRewrite: () -> Unit, onRetry: () -> Unit,
) {
    val theme = LocalMauriceTheme.current
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.padding(horizontal = 14.dp).fillMaxWidth().clip(shape).background(theme.surface).border(0.5.dp, theme.ruleHard, shape).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GridLabel(L("carnet.summary"), 9.5.sp)
            if (summarizing) { CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = theme.inkMute); GridLabel(L("carnet.entry.summary.reading"), 9.5.sp) }
            Spacer(Modifier.weight(1f))
            if (summary != null && !summarizing && !entry.isForeign && entry.source?.captured == true) Text("↻ " + L("carnet.rewrite"), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = theme.inkSoft, modifier = Modifier.clickable(onClick = onRewrite).accessibilityName(L("carnet.rewrite_summary")))
        }
        val long = summary?.long?.trim().orEmpty()
        when {
            loading && summary == null -> Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = theme.inkMute) }
            summary != null && (summary.text.isNotEmpty() || long.isNotEmpty()) -> {
                val shown = if ((open || summary.text.isEmpty()) && long.isNotEmpty()) long else summary.text
                Box(Modifier.graphicsLayer { alpha = if (summarizing) 0.55f else 1f }) { MarkdownBlocks(shown, serif = true) }
                if (!open && long.isNotEmpty() && summary.text.isNotEmpty()) Text(L("carnet.entry.see_more"), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = theme.ink, textDecoration = TextDecoration.Underline, modifier = Modifier.clickable(onClick = onToggle))
                else if (open) Text(L("carnet.entry.see_less"), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = theme.inkSoft, modifier = Modifier.clickable(onClick = onToggle))
            }
            entry.source?.captured == true -> if (!summarizing) Text(L("carnet.entry.summary.none"), fontSize = 14.sp, color = theme.inkSoft)
            else -> Text(L("carnet.entry.summary.refused"), fontSize = 13.5.sp, color = theme.inkSoft)
        }
        if (error != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(error, fontSize = 12.5.sp, color = theme.inkSoft, modifier = Modifier.weight(1f))
            GridButton(L("carnet.try_again"), small = true, onClick = onRetry)
        }
    }
}

/** The two-faced sheet, turned over at its folded corner. */
@Composable
private fun Sheet(entry: ShelfEntry, face: Face, angle: Float, loading: Boolean, error: String?, detail: EntryDetail?, conversations: List<EntryConversation>, onTurn: () -> Unit, onNote: (MineItem, Boolean) -> Unit = { _, _ -> }) {
    val theme = LocalMauriceTheme.current
    val shape = remember { SheetShape() }
    val back = when (face) {
        Face.Mine -> if (entry.shared == null) Back.Blank else Back.Shared
        Face.Shared -> if (entry.mine == null) Back.Blank else Back.Mine
    }
    Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().graphicsLayer { rotationY = angle; cameraDistance = 24f * density }) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 380.dp).paper(face, shape, theme).padding(16.dp).padding(bottom = 46.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (face == Face.Mine) MineFace(entry, loading, error, detail, conversations, onNote) else SharedFace(entry, loading, detail)
        }
        if (!entry.isForeign) Row(Modifier.align(Alignment.BottomEnd).clickable(onClick = onTurn).accessibilityName(L("carnet.entry.turn_over")), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.padding(bottom = 8.dp, end = 8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(L("carnet.entry.turn_over"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = theme.ink)
                when (face) {
                    Face.Mine -> entry.shared?.let { StateMark(it.published) } ?: GridLabel(L("carnet.entry.blank"), 9.5.sp)
                    Face.Shared -> if (entry.mine != null) GridLabel(noteCount(entry.noteCount), 9.5.sp) else GridLabel(L("carnet.entry.blank"), 9.5.sp)
                }
            }
            FoldedCorner(back)
        }
    }
}

@Composable
private fun MineFace(entry: ShelfEntry, loading: Boolean, error: String?, detail: EntryDetail?, conversations: List<EntryConversation>, onNote: (MineItem, Boolean) -> Unit) {
    val theme = LocalMauriceTheme.current
    val audience = entry.owner?.let { L("article.shared_by", it.displayName) }
        ?: entry.participants.orEmpty().map { it.displayName }.let { if (it.isEmpty()) L("entry.audience.only_you") else L("entry.audience.you_and", it.joinToString(", ")) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(DomainGlyphs.of(if (entry.isShared) "person.2" else "lock.fill"), null, tint = theme.inkMute, modifier = Modifier.size(10.dp))
        Spacer(Modifier.width(5.dp))
        GridLabel(if (entry.noteCount > 0) "$audience · ${noteCount(entry.noteCount)}" else audience, 9.5.sp)
    }
    val mine = detail?.mine
    when {
        loading -> Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = theme.inkMute) }
        error != null -> Text(error, fontSize = 13.sp, color = theme.inkSoft)
        mine != null && (mine.prose.isNotEmpty() || mine.items.isNotEmpty()) || conversations.isNotEmpty() -> {
            if (mine != null && mine.prose.isNotEmpty()) MarkdownBlocks(mine.prose, serif = false)
            for (item in mine?.items.orEmpty()) NoteActions(item, onEdit = { onNote(item, false) }, onDelete = { onNote(item, true) }) { MineItemView(item, signed = entry.isShared) }
            if (conversations.isNotEmpty()) ConversationsSection(entry, conversations)
        }
        else -> Column(Modifier.fillMaxWidth().padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(L("carnet.nothing_here"), fontSize = 14.sp, color = theme.inkSoft)
            Text(if (entry.source?.isReadable == true) L("carnet.entry.mine.readable") else L("carnet.entry.mine.other"), fontSize = 13.sp, color = theme.inkMute)
        }
    }
}

@Composable
private fun SharedFace(entry: ShelfEntry, loading: Boolean, detail: EntryDetail?) {
    val theme = LocalMauriceTheme.current
    val state = entry.shared
    if (state != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        StateMark(state.published)
        if (!state.published) GridLabel(L("carnet.entry.not_on_site"), 9.5.sp)
    } else GridLabel(L("carnet.entry.to_share"), 9.5.sp)
    val shared = detail?.shared
    when {
        loading -> Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = theme.inkMute) }
        shared != null -> {
            shared.title?.let { Text(it, style = wordmark(22.sp), color = theme.ink) }
            MarkdownBlocks(withoutTitle(shared.body, shared.title), serif = true, links = shared.links)
        }
        else -> Column(Modifier.fillMaxWidth().padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(L("carnet.entry.shared.empty"), fontSize = 14.sp, color = theme.inkSoft)
            Text(L("carnet.entry.shared.hint"), fontSize = 13.sp, color = theme.inkMute)
        }
    }
}

/** The shared body without its `# title` line, which the page shows apart. */
internal fun withoutTitle(body: String, title: String?): String =
    if (title == null) body else body.lines().filterNot { it.startsWith("# ") }.joinToString("\n").trim()

/**
 * Most of the time the summary is all one reads of a thing: the page ends on
 * what to do with it then — put it away from the list, or delete it. Archiving
 * is the member's own and changes nothing in the garden; another member's
 * entry, or a book of the library with no entry, has nothing of this member's
 * to delete.
 */
@Composable
private fun PutAway(entry: ShelfEntry, working: Boolean, onArchive: (Boolean) -> Unit, onDelete: () -> Unit) {
    val archived = entry.archived == true
    Row(Modifier.fillMaxWidth().padding(top = 28.dp).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
        GridButton(L(if (archived) "entry.unarchive" else "entry.archive"), symbol = if (archived) "tray.and.arrow.up" else "archivebox", enabled = !working) { onArchive(!archived) }
        if (!entry.isLibraryOnly && !entry.isForeign) GridButton(L("entry.delete"), symbol = "trash", enabled = !working, destructive = true, onClick = onDelete)
    }
}

/** What a delete takes with it, said before it is confirmed — on the page and on the list's swipe. */
internal fun deleteWarning(entry: ShelfEntry, strings: Strings): String = buildList {
    add(strings("entry.delete.notes"))
    if (entry.publishedSomewhere) add(strings("entry.delete.online"))
    if (entry.source?.isBook == true) add(strings("entry.delete.book"))
}.joinToString(" ")

/** "Add a note…", and Maurice's mark to talk about it. */
@Composable
internal fun WritePanel(onNote: () -> Unit, onAsk: (() -> Unit)?) {
    val theme = LocalMauriceTheme.current
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.weight(1f).height(44.dp).minePaper(theme).clip(CircleShape).clickable(onClick = onNote).accessibilityName(L("note.panel.add")).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(L("note.panel.placeholder"), fontSize = 15.sp, color = theme.inkMute, modifier = Modifier.weight(1f))
            Text("“", fontSize = 18.sp, color = theme.inkSoft)
        }
        if (onAsk != null) MauriceMark(44.dp, Modifier.clip(CircleShape).clickable(onClick = onAsk).accessibilityLabel(L("note.panel.ask_maurice")))
    }
}

/** Maurice's hat on an ink disc. */
@Composable
fun MauriceMark(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val theme = LocalMauriceTheme.current
    Box(modifier.size(size).clip(CircleShape).background(theme.ink), contentAlignment = Alignment.Center) { BoaterHat(size = size * 0.56f, color = theme.bg) }
}

/** The shared face's footer: edit, its state on the site, publish, or unpublish and open. */
@Composable
private fun SharedPanel(
    entry: ShelfEntry, detail: EntryDetail?, deploy: DeployState?, lastPublish: Boolean?, landed: Boolean, working: Boolean, writeError: String?, hasSite: Boolean,
    onEdit: () -> Unit, onPublish: () -> Unit, onUnpublish: () -> Unit, onOpen: (String) -> Unit,
) {
    val theme = LocalMauriceTheme.current
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        writeError?.let { Text(it, fontSize = 12.5.sp, color = theme.inkSoft, modifier = Modifier.padding(horizontal = 6.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val shared = detail?.shared
            if (shared != null) {
                GridIconButton(DomainGlyphs.of("pencil"), L("carnet.edit"), onClick = onEdit)
                val line = when {
                    deploy?.inProgress == true -> when (lastPublish) { true -> L("carnet.deploy.publishing"); false -> L("carnet.deploy.unpublishing"); null -> L("carnet.deploy.updating") }
                    deploy?.failed == true -> when (lastPublish) { true -> L("carnet.deploy.publish_failed"); false -> L("carnet.deploy.unpublish_failed"); null -> L("carnet.deploy.update_failed") }
                    landed -> if (lastPublish == false) L("carnet.offline") else L("carnet.online")
                    shared.publicUrl != null -> shared.publicUrl!!.removePrefix("https://")
                    else -> if (shared.published) L("carnet.state.published") else L("carnet.state.draft")
                }
                Text(line, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp, color = theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                when {
                    working || deploy?.inProgress == true -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = theme.inkMute)
                    landed -> Text("✓", fontSize = 15.sp, color = theme.inkSoft)
                    !shared.published && hasSite -> GridButton(L("entry.publish"), symbol = "globe", primary = true, small = true, onClick = onPublish)
                    else -> {
                        // Online: take it off, beside going to see it — by its
                        // icon, the line being narrow.
                        if (shared.published && hasSite) GridIconButton(DomainGlyphs.of("eye.slash"), L("entry.menu.unpublish"), onClick = onUnpublish)
                        shared.publicUrl?.let { u -> GridButton(L("add.open"), symbol = "arrow.up.forward", small = true) { onOpen(u) } }
                    }
                }
            } else {
                Text(if (entry.isLibraryOnly) L("carnet.entry.first_note") else L("carnet.entry.nothing_written"), fontSize = 13.5.sp, color = theme.inkMute, modifier = Modifier.weight(1f).padding(start = 8.dp))
                if (!entry.isLibraryOnly) GridButton(L("carnet.entry.write"), symbol = "pencil", primary = true, small = true, onClick = onEdit)
            }
        }
    }
}

/** More: unpublish, refresh the details, the page in the browser, Obsidian. */
@Composable
private fun MoreMenu(session: Session, nav: Navigator, entry: ShelfEntry, detail: EntryDetail?, face: Face, working: Boolean, onUnpublish: () -> Unit, onLinkBook: () -> Unit, onRefresh: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    var open by remember { mutableStateOf(false) }
    val file = (if (face == Face.Mine) detail?.mine?.file else detail?.shared?.file)?.takeIf { it.isNotEmpty() } ?: detail?.shared?.file?.takeIf { it.isNotEmpty() } ?: detail?.mine?.file?.takeIf { it.isNotEmpty() }
    val web = (if (face == Face.Mine) detail?.mine?.webPath else detail?.shared?.webPath) ?: detail?.shared?.webPath ?: detail?.mine?.webPath
    Box {
        if (working) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = theme.inkMute) }
        else GridIconButton(Icons.Default.MoreVert, L("entry.menu.more"), enabled = !entry.isLibraryOnly) { open = true }
        DropdownMenu(open, { open = false }, containerColor = theme.surface) {
            // Whichever face is up: a published entry can be taken off from either.
            if (detail?.shared?.published == true && nav.site != null) DropdownMenuItem(text = { Text(L("entry.menu.unpublish")) }, onClick = { open = false; onUnpublish() })
            DropdownMenuItem(text = { Text(L("entry.menu.refresh")) }, onClick = { open = false; onRefresh() })
            // A book: the library's copy it is about, when the title did not find it, or found the wrong one.
            if (entry.collection == "books") DropdownMenuItem(
                text = { Text(L(if (entry.source?.isBook == true) "entry.menu.change_book" else "entry.menu.link_book")) },
                onClick = { open = false; onLinkBook() },
            )
            if (web != null) DropdownMenuItem(text = { Text(L("entry.menu.browser")) }, onClick = { open = false; scope.launch { session.signedInUrl(web)?.let(uri::openUri) } })
            if (file != null && nav.username.isNotEmpty()) {
                val obsidian = "obsidian://open?vault=${nav.username}&file=" + file.removeSuffix(".md").encodeUrl()
                DropdownMenuItem(text = { Text(L("entry.menu.obsidian")) }, onClick = { open = false; runCatching { uri.openUri(obsidian) } })
                DropdownMenuItem(text = { Text(L("entry.menu.obsidian_link")) }, onClick = { open = false; scope.launch { clipboard.setClipEntry(plainTextClip(obsidian)) } })
            }
        }
    }
}

/** Percent-encode all but `A–Z a–z 0–9 - . _ ~`. */
private fun String.encodeUrl(): String = encodeToByteArray().joinToString("") { b ->
    val c = (b.toInt() and 0xff).toChar()
    if (c.isLetterOrDigit() && c.code < 128 || c in "-._~") c.toString() else "%" + (b.toInt() and 0xff).toString(16).uppercase().padStart(2, '0')
}
