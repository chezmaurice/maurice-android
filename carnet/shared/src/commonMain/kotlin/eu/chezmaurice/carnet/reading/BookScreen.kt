package eu.chezmaurice.carnet.reading

import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalStrings
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.carnet.Navigator
import eu.chezmaurice.carnet.Route
import eu.chezmaurice.carnet.ui.BookCover
import eu.chezmaurice.carnet.ui.GridBar
import eu.chezmaurice.carnet.ui.GridButton
import eu.chezmaurice.carnet.ui.GridIconButton
import eu.chezmaurice.carnet.ui.GridLabel
import eu.chezmaurice.carnet.ui.GridLoading
import eu.chezmaurice.carnet.ui.GridMessage
import eu.chezmaurice.carnet.ui.GridRule
import eu.chezmaurice.carnet.ui.GridSheet
import eu.chezmaurice.carnet.ui.GridTitle
import eu.chezmaurice.carnet.ui.LocalApi
import eu.chezmaurice.carnet.ui.PAGE
import eu.chezmaurice.core.carnet.CalibreApi
import eu.chezmaurice.core.carnet.Chapter
import eu.chezmaurice.core.carnet.Highlight
import eu.chezmaurice.core.domains.DomainsApi
import eu.chezmaurice.core.model.DomainSummary
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.wordmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private sealed interface OfflineState {
    data object None : OfflineState
    data class Downloading(val done: Int, val total: Int) : OfflineState
    data class Downloaded(val bytes: Long) : OfflineState
}

/**
 * One book (`BookDetailView`): its cover, its chapters with where you are,
 * and in reach at the bottom, Resume and the book's tools — highlights,
 * the reading drawer (pause, reset), the book's drawer (offline copy,
 * summaries, the companion that follows it in Maurice).
 */
@Composable
fun BookScreen(nav: Navigator, bookId: Int, title: String, byline: String?) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val api = LocalApi.current ?: return
    val calibre = remember(api) { CalibreApi(api) }
    val cache = remember(api, bookId) { BookCache(api.baseUrl, bookId) }
    val scope = rememberCoroutineScope()
    val positions = nav.positions

    var chapters by remember { mutableStateOf(cache.chapters.orEmpty()) }
    var loading by remember { mutableStateOf(chapters.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var serverSlug by remember { mutableStateOf<String?>(null) }
    var serverSummary by remember { mutableStateOf(false) }
    var highlightCount by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(positions.isPaused(bookId)) }
    var offline by remember { mutableStateOf<OfflineState>(if (cache.isDownloaded) OfflineState.Downloaded(cache.size) else OfflineState.None) }
    var download by remember { mutableStateOf<Job?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var summarizing by remember { mutableStateOf(false) }
    var extracting by remember { mutableStateOf(false) }
    var extractError by remember { mutableStateOf<String?>(null) }
    var companion by remember { mutableStateOf<DomainSummary?>(null) }
    var companionKnown by remember { mutableStateOf(false) }
    var following by remember { mutableStateOf(false) }
    var followError by remember { mutableStateOf<String?>(null) }
    var readingDrawer by remember { mutableStateOf(false) }
    var bookDrawer by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    // Read again when the reader closes: it moves the place and caches chapters.
    val returnedAt = nav.stack.size

    // The local record wins (offline, reconciled on load); the server's is the fallback.
    val resumeSlug = positions.get(bookId)?.chapterSlug ?: serverSlug
    val current = resumeSlug?.let { s -> chapters.indexOfFirst { it.slug == s }.takeIf { it >= 0 } }
    val resumeSummary = positions.get(bookId)?.lastView?.let { it == "summary" } ?: serverSummary
    // How far, over this list: the server's chapter index counts front matter the list hides.
    val fraction = current?.let { (it + 1).toDouble() / chapters.size }
    val summarized = chapters.count { it.hasSummary }

    suspend fun loadCompanion() {
        runCatching { DomainsApi(api).overview() }.getOrNull()?.let { ov -> companion = ov.companions.firstOrNull { it.bookId == bookId }; companionKnown = true }
    }

    LaunchedEffect(returnedAt) {
        if (nav.stack.lastOrNull() !is Route.Book) return@LaunchedEffect
        error = null
        try {
            chapters = calibre.chapters(bookId)
            val progress = runCatching { calibre.progress(bookId) }.getOrNull()
            serverSlug = progress?.chapterSlug
            serverSummary = progress?.view == "summary"
            // The server holds the truth about tracking; mirrored here so the reader honours it offline.
            paused = progress?.paused ?: positions.isPaused(bookId)
            positions.setPaused(bookId, paused)
            if (!paused) positions.reconcile(bookId, progress)
            highlightCount = runCatching { calibre.highlights(bookId).size }.getOrDefault(highlightCount)
            if (!companionKnown) scope.launch { loadCompanion() }
        } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            if (chapters.isEmpty()) error = strings("carnet.book.offline_unavailable")
        }
        loading = false
    }

    fun read(index: Int, summary: Boolean) = nav.push(Route.Reader(bookId, title, byline, chapters, index, summary && chapters.getOrNull(index)?.hasSummary == true))

    fun startExtract() = scope.launch {
        extracting = true; extractError = null
        try { calibre.extract(bookId) } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            extractError = strings("carnet.book.extract_failed_start", CalibreApi.error(e)); extracting = false; return@launch
        }
        repeat(40) {
            delay(3_000)
            runCatching { calibre.chapters(bookId) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { chapters = it; extracting = false; return@launch }
            val job = runCatching { calibre.status(bookId).job }.getOrNull()
            if (job?.state == "error") { extracting = false; extractError = strings("carnet.book.extract_failed", job.error ?: strings("carnet.unknown_error")); return@launch }
        }
        extracting = false; extractError = strings("carnet.book.extract_unfinished")
    }

    fun startSummarize() = scope.launch {
        summarizing = true
        try { calibre.summarize(bookId) } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = CalibreApi.error(e); summarizing = false; return@launch }
        while (summarizing) {
            delay(3_000)
            val st = runCatching { calibre.status(bookId) }.getOrNull()
            val refreshed = runCatching { calibre.chapters(bookId) }.getOrNull()
            val done = st?.isComplete == true || refreshed?.let { it.isNotEmpty() && it.all { c -> c.hasSummary } } == true
            if (st?.job?.state == "error" && !done) { summarizing = false; error = strings("carnet.book.summarize_failed", st.job?.error ?: strings("carnet.unknown_error")); return@launch }
            refreshed?.let { chapters = it }
            if (done) summarizing = false
        }
    }

    fun startDownload() {
        offline = OfflineState.Downloading(0, chapters.size); downloadError = null
        val list = chapters
        download = scope.launch {
            try {
                cache.download(calibre, list) { d, t -> offline = OfflineState.Downloading(d, t) }
                offline = OfflineState.Downloaded(cache.size)
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                cache.remove(); offline = OfflineState.None
                downloadError = strings("carnet.book.download_failed", CalibreApi.error(e))
            }
        }
    }
    fun removeOffline() { download?.cancel(); cache.remove(); offline = OfflineState.None }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            GridIconButton(Icons.AutoMirrored.Filled.ArrowBack, L("carnet.back")) { nav.pop() }
        }
        when {
            loading -> GridLoading(Modifier.weight(1f))
            error != null && chapters.isEmpty() -> Box(Modifier.weight(1f)) { GridMessage(error!!) }
            else -> LazyColumn(Modifier.weight(1f)) {
                item(key = "head") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = PAGE).padding(top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        BookCover(bookId, title.trim().take(1).uppercase(), 78.dp, 110.dp)
                        Column {
                            Text(title, style = wordmark(22.sp), color = theme.ink)
                            if (!byline.isNullOrEmpty()) Text(byline, fontSize = 13.sp, color = theme.inkMute, modifier = Modifier.padding(top = 4.dp))
                            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Stat(L("carnet.book.chapters"), "${chapters.size}"); Stat(L("carnet.book.summarized"), "$summarized")
                            }
                        }
                    }
                }
                if (chapters.isEmpty()) item(key = "extract") {
                    Column(
                        Modifier.padding(horizontal = PAGE).padding(top = 4.dp).fillMaxWidth().background(theme.surface).border(0.5.dp, theme.rule).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        GridLabel(L("carnet.book.not_split"))
                        Text(L("carnet.book.not_split_hint"), fontSize = 13.5.sp, color = theme.inkSoft)
                        if (extracting) Working(L("carnet.book.extracting")) else GridButton(L("carnet.book.extract"), symbol = "scissors", primary = true) { startExtract() }
                        extractError?.let { Text(it, fontSize = 12.5.sp, color = theme.inkSoft) }
                    }
                } else {
                    item(key = "status") {
                        Column(Modifier.padding(horizontal = PAGE)) {
                            val line = when (val o = offline) {
                                is OfflineState.Downloading -> L("carnet.book.downloading_progress", o.done, o.total)
                                else -> if (summarizing) L("carnet.book.summarizing", summarized, chapters.size) else null
                            }
                            line?.let { Box(Modifier.padding(bottom = 12.dp)) { Working(it) } }
                            downloadError?.let { Text(it, fontSize = 11.sp, color = theme.inkSoft, modifier = Modifier.padding(bottom = 12.dp)) }
                            GridLabel(L("carnet.book.chapters"), modifier = Modifier.padding(bottom = 8.dp))
                        }
                    }
                    itemsIndexed(chapters, key = { _, c -> c.slug }) { i, ch ->
                        Column {
                            GridRule()
                            ChapterRow(ch, i + 1, now = i == current, read = current != null && i < current, cached = cache.isCached(ch.slug)) {
                                read(i, resumeSummary)
                            }
                        }
                    }
                    item(key = "end") { GridRule(); Spacer(Modifier.height(24.dp)) }
                }
            }
        }
        if (chapters.isNotEmpty()) Column(
            Modifier.fillMaxWidth().background(theme.surface).navigationBarsPadding().padding(horizontal = PAGE).padding(top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(current?.let { chapters[it].title } ?: chapters.first().title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                fraction?.let { GridLabel("${(it * 100).roundToInt()}%", 9.5.sp, theme.inkSoft) }
            }
            fraction?.let { GridBar(it) }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                GridIconButton(DomainGlyphs.of("book"), if (current != null) L("carnet.reading.resume") else L("carnet.book.start_reading"), on = true, size = 44.dp) { read(current ?: 0, resumeSummary) }
                Box(Modifier.alpha(if (highlightCount == 0) 0.4f else 1f)) {
                    GridIconButton(DomainGlyphs.of("highlighter"), L("carnet.book.highlights"), size = 44.dp, enabled = highlightCount > 0) { nav.push(Route.Highlights(bookId, title, byline, chapters)) }
                }
                GridIconButton(DomainGlyphs.of(if (paused) "pause.circle" else "bookmark"), L("carnet.book.reading"), on = paused, size = 44.dp) { readingDrawer = true }
                GridIconButton(DomainGlyphs.of("ellipsis"), L("carnet.book.this_book"), size = 44.dp) { bookDrawer = true }
            }
        }
    }

    if (readingDrawer) GridSheet(onDismiss = { readingDrawer = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            GridLabel(L("carnet.book.reading"))
            Text(current?.let { L("carnet.book.chapter_of", it + 1, chapters.size) } ?: L("carnet.book.not_started"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = theme.ink, modifier = Modifier.padding(top = 4.dp))
            current?.let { Text(chapters[it].title, fontSize = 13.sp, color = theme.inkMute, maxLines = 2) }
        }
        GridRule()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(L("carnet.book.pause"), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = theme.ink)
                Text(L("carnet.book.pause_hint"), fontSize = 12.sp, color = theme.inkMute)
            }
            Switch(paused, onCheckedChange = { on ->
                // Locally first: the reader writes offline and resumes from the local record.
                paused = on; positions.setPaused(bookId, on)
                scope.launch { runCatching { calibre.setTracking(bookId, on) } }
            }, colors = SwitchDefaults.colors(checkedTrackColor = theme.ink, checkedThumbColor = theme.bg))
        }
        Row(
            Modifier.fillMaxWidth().border(0.5.dp, theme.ruleHard).clickable(enabled = current != null || paused) { confirmReset = true }.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(DomainGlyphs.of("arrow.counterclockwise"), null, tint = theme.ink, modifier = Modifier.size(15.dp))
            Text(L("carnet.book.reset"), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = theme.ink)
        }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text(L("carnet.book.reset_confirm")) },
        confirmButton = { TextButton(onClick = {
            confirmReset = false; readingDrawer = false
            positions.clear(bookId); serverSlug = null
            scope.launch { runCatching { calibre.resetProgress(bookId) } }
        }) { Text(L("carnet.book.reset"), color = theme.ink) } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(L("common.cancel"), color = theme.inkSoft) } },
        containerColor = theme.surface,
    )
    if (bookDrawer) GridSheet(onDismiss = { bookDrawer = false }) {
        GridLabel(L("carnet.book.this_book"))
        DrawerRow("arrow.down.circle", L("carnet.book.offline"), when (val o = offline) {
            OfflineState.None -> L("carnet.book.not_downloaded")
            is OfflineState.Downloading -> L("carnet.book.downloading")
            is OfflineState.Downloaded -> L("carnet.book.downloaded", chapters.size, formatSize(o.bytes, eu.chezmaurice.maurice.consent.LocalAppLanguage.current))
        }) {
            val o = offline
            if (o is OfflineState.Downloading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GridLabel("${o.done}/${o.total}", 9.5.sp, theme.inkSoft); Spinner()
            } else Switch(o is OfflineState.Downloaded, onCheckedChange = { if (it) startDownload() else removeOffline() },
                colors = SwitchDefaults.colors(checkedTrackColor = theme.ink, checkedThumbColor = theme.bg))
        }
        val missing = chapters.size - summarized
        if (missing > 0) Row(Modifier.fillMaxWidth().background(theme.bg).border(0.5.dp, theme.rule).padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(if (missing == 1) L("carnet.book.missing.one") else L("carnet.book.missing.other", missing), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = theme.ink)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    GridBar(if (chapters.isEmpty()) 0.0 else summarized.toDouble() / chapters.size, width = 120.dp)
                    GridLabel("$summarized/${chapters.size}", 9.5.sp, theme.inkSoft)
                }
            }
            if (summarizing) Spinner() else GridButton(L("carnet.book.all"), symbol = "sparkle", primary = true, small = true) { startSummarize() }
        }
        if (companionKnown) DrawerRow(
            "book.pages", if (companion == null) L("carnet.book.read_with") else L("carnet.book.followed"),
            followError ?: companion?.let { c -> if (c.conversationId == null) L("carnet.book.companion_waiting", c.name) else L("carnet.book.companion_app", c.name) }
                ?: L("carnet.book.companion_hint"),
        ) {
            if (following) Spinner() else if (companion == null) GridButton(L("carnet.book.follow"), symbol = "plus", small = true) {
                following = true; followError = null
                scope.launch {
                    try { calibre.follow(bookId, title); loadCompanion() } catch (e: CancellationException) { throw e } catch (e: Throwable) { followError = strings("carnet.book.follow_failed", CalibreApi.error(e)) }
                    following = false
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) = Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
    GridLabel(label, 9.sp)
    Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = LocalMauriceTheme.current.ink)
}

@Composable
private fun Spinner() = CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalMauriceTheme.current.inkMute)

@Composable
private fun Working(line: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Spinner(); GridLabel(line, 9.5.sp, LocalMauriceTheme.current.inkSoft)
}

@Composable
private fun DrawerRow(symbol: String, title: String, subtitle: String, trailing: @Composable () -> Unit) {
    val theme = LocalMauriceTheme.current
    Row(Modifier.fillMaxWidth().border(0.5.dp, theme.ruleHard).padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(DomainGlyphs.of(symbol), null, tint = theme.inkSoft, modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = theme.ink)
            GridLabel(subtitle, 9.5.sp)
        }
        trailing()
    }
}

/** A chapter in the list: its number, its title; on the device, summarized, read, now. */
@Composable
private fun ChapterRow(chapter: Chapter, number: Int, now: Boolean, read: Boolean, cached: Boolean, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Row(
        Modifier.fillMaxWidth().background(if (now) theme.surfaceAlt else androidx.compose.ui.graphics.Color.Transparent).clickable(onClick = onClick).padding(horizontal = PAGE, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GridLabel(number.toString().padStart(2, '0'), 11.sp, modifier = Modifier.width(22.dp))
        Text(chapter.title, fontSize = 14.sp, color = if (read) theme.inkMute else theme.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (cached) Icon(DomainGlyphs.of("arrow.down.circle.fill"), L("carnet.chapter.on_device"), tint = theme.inkMute, modifier = Modifier.size(13.dp))
        if (chapter.hasSummary) Icon(DomainGlyphs.of("sparkle"), L("carnet.chapter.summarized"), tint = theme.ink, modifier = Modifier.size(14.dp))
        if (read) Icon(DomainGlyphs.of("checkmark"), L("carnet.reading.done"), tint = theme.inkMute, modifier = Modifier.size(13.dp))
        if (now) GridLabel(L("carnet.chapter.now"), 9.sp, theme.ink)
    }
}

/** Straight into reading (`BookResumeView`): the chapter list, the place brought up to the server's, then the reader. */
@Composable
fun BookResume(nav: Navigator, bookId: Int, title: String, byline: String?) {
    val strings = LocalStrings.current
    val api = LocalApi.current ?: return
    val calibre = remember(api) { CalibreApi(api) }
    var target by remember { mutableStateOf<Triple<List<Chapter>, Int, Boolean>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val cache = BookCache(api.baseUrl, bookId)
        val list = runCatching { calibre.chapters(bookId) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: cache.chapters.orEmpty()
        if (list.isEmpty()) { error = strings("carnet.book.no_chapters"); return@LaunchedEffect }
        val server = runCatching { calibre.progress(bookId) }.getOrNull()
        val local = if (server?.paused == true) nav.positions.get(bookId) else nav.positions.reconcile(bookId, server)
        val slug = local?.chapterSlug ?: server?.chapterSlug
        val start = slug?.let { s -> list.indexOfFirst { it.slug == s }.takeIf { it >= 0 } } ?: 0
        target = Triple(list, start, (local?.lastView ?: server?.view) == "summary" && list[start].hasSummary)
    }
    val t = target
    if (t != null) BookReader(nav, bookId, title, byline, t.first, t.second, t.third)
    else Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { GridIconButton(Icons.AutoMirrored.Filled.ArrowBack, L("carnet.back")) { nav.pop() } }
        error?.let { GridMessage(it) } ?: GridLoading()
    }
}

/** Every passage marked in a book (`HighlightsView`); a tap goes back to it. */
@Composable
fun HighlightsScreen(nav: Navigator, bookId: Int, title: String, byline: String?, chapters: List<Chapter>) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val api = LocalApi.current ?: return
    val calibre = remember(api) { CalibreApi(api) }
    var highlights by remember { mutableStateOf<List<Highlight>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(nav.stack.size) {
        try { highlights = calibre.highlights(bookId) } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = strings("carnet.highlights.load_failed", CalibreApi.error(e)) }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { GridIconButton(Icons.AutoMirrored.Filled.ArrowBack, L("carnet.back")) { nav.pop() } }
        GridTitle(L("carnet.book.highlights"), highlights?.size?.toString())
        GridRule()
        val list = highlights
        when {
            error != null -> GridMessage(error!!)
            list == null -> GridLoading()
            list.isEmpty() -> GridMessage(L("carnet.highlights.none"))
            else -> LazyColumn {
                items(list.size, key = { list[it].id }) { i ->
                    val h = list[i]
                    val index = chapters.indexOfFirst { it.slug == h.chapterSlug }.coerceAtLeast(0)
                    Column(
                        Modifier.fillMaxWidth().clickable { nav.push(Route.Reader(bookId, title, byline, chapters, index, h.readerView == "summary")) }.padding(horizontal = PAGE, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                            Box(Modifier.width(3.dp).fillMaxSize().background(highlightColor(h.color, theme.ink, theme.isDark)))
                            Spacer(Modifier.width(11.dp))
                            Text(h.quote, fontSize = 14.5.sp, color = theme.ink)
                        }
                        h.note?.takeIf { it.isNotEmpty() }?.let { Text("✎ $it", fontSize = 13.sp, color = theme.inkSoft, modifier = Modifier.padding(start = 14.dp)) }
                        GridLabel(L("carnet.highlights.jump", chapters.getOrNull(index)?.title ?: h.chapterSlug), 9.sp, modifier = Modifier.padding(start = 14.dp))
                    }
                    GridRule()
                }
            }
        }
    }
}
