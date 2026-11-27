package eu.chezmaurice.carnet.reading

import androidx.compose.ui.semantics.Role
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.carnet.ui.highlightCount
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.carnet.Navigator
import eu.chezmaurice.carnet.entry.NoteSheet
import eu.chezmaurice.carnet.ui.GridBar
import eu.chezmaurice.carnet.ui.GridButton
import eu.chezmaurice.carnet.ui.GridIconButton
import eu.chezmaurice.carnet.ui.GridLabel
import eu.chezmaurice.carnet.ui.GridLoading
import eu.chezmaurice.carnet.ui.GridMessage
import eu.chezmaurice.carnet.ui.GridRule
import eu.chezmaurice.carnet.ui.GridSheet
import eu.chezmaurice.carnet.ui.LocalApi
import eu.chezmaurice.core.carnet.CalibreApi
import eu.chezmaurice.core.carnet.Chapter
import eu.chezmaurice.core.carnet.GardenApi
import eu.chezmaurice.core.carnet.Highlight
import eu.chezmaurice.core.carnet.HighlightConversation
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.carnet.entry.MauriceMark
import eu.chezmaurice.ui.accessibilityLabel
import androidx.compose.foundation.shape.CircleShape
import eu.chezmaurice.core.carnet.Positions
import eu.chezmaurice.core.carnet.charOffsetOfParagraph
import eu.chezmaurice.core.carnet.paragraphOfChar
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.LocalMauriceTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A book's chapter (`ReaderView`): its text or its summary, three sizes,
 * the chapter's bookmark and its note, passage highlights, a note to the
 * book's entry. The place is kept as it moves — on the device first, so it
 * works offline, and on the server unless tracking is paused.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun BookReader(nav: Navigator, bookId: Int, title: String, byline: String?, chapters: List<Chapter>, startIndex: Int, startSummary: Boolean) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val api = LocalApi.current ?: return
    val calibre = remember(api) { CalibreApi(api) }
    val resonances: eu.chezmaurice.core.carnet.ResonancesApi? = remember(api) { eu.chezmaurice.core.carnet.ResonancesApi(api) }
    val garden = remember(api) { GardenApi(api) }
    val cache = remember(api, bookId) { BookCache(api.baseUrl, bookId) }
    val scope = rememberCoroutineScope()
    val positions = nav.positions

    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, maxOf(0, chapters.size - 1))) }
    var summary by remember { mutableStateOf(startSummary && chapters.getOrNull(startIndex)?.hasSummary == true) }
    var size by remember { mutableIntStateOf(ReaderSize.get()) }
    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var highlights by remember { mutableStateOf<List<Highlight>>(emptyList()) }
    var bookmarkId by remember { mutableStateOf<Int?>(null) }
    var bookmarkNote by remember { mutableStateOf("") }
    var notingBookmark by remember { mutableStateOf(false) }
    var takingNote by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<HighlightEdit?>(null) }
    var restore by remember { mutableIntStateOf(0) }
    // The live place, in both views; a plain holder, so scrolling does not recompose.
    val place = remember { intArrayOf(0, 0) }
    var saving by remember { mutableStateOf<Job?>(null) }

    val chapter = chapters.getOrNull(index) ?: return
    val view = if (summary) "summary" else "full"
    val shown = highlights.filter { it.chapterSlug == chapter.slug && it.readerView == view }
    val progress = if (chapters.isEmpty()) 0.0 else (index + 1).toDouble() / chapters.size

    /** Locally first, then the server; not while tracking is paused. */
    fun persist() {
        if (positions.isPaused(bookId)) return
        positions.save(bookId, Positions.Record(chapter.index, chapter.slug, view, place[0], place[1], Clock.System.now().toEpochMilliseconds()))
        val at = if (summary) place[1] else place[0]
        val (i, s, v) = Triple(chapter.index, chapter.slug, view)
        scope.launch { runCatching { calibre.setProgress(bookId, i, s, v, at.toDouble()) } }
    }
    fun flush() { saving?.cancel(); persist() }
    fun schedule() { saving?.cancel(); saving = scope.launch { delay(1_000); persist() } }
    fun go(delta: Int) {
        val next = index + delta
        if (next !in chapters.indices) return
        flush()
        index = next
        if (!chapters[next].hasSummary) summary = false
    }

    DisposableEffect(Unit) { onDispose { saving?.cancel(); persist() } }

    // Maurice, from here: on the chapter open, or on a passage. The conversation
    // is held from the book's entry (made if it had none), with the fiche and the
    // book up to where the member is; the position is sent first.
    val talk = rememberReaderTalk()
    fun attach(highlightId: Int, conversationId: String) {
        highlights = highlights.map { if (it.id == highlightId) it.copy(conversationId = conversationId, conversation = HighlightConversation(conversationId)) else it }
    }
    fun talkToMaurice(highlightId: Int? = null) {
        val slug = chapter.slug
        talk.open(start = {
            garden.startBookConversation(bookId, slug, highlightId).also { id -> if (highlightId != null) attach(highlightId, id) }
        }, opened = { flush() })
    }
    /** An update answers without the passage's conversation: keep the one listed. */
    fun updated(u: Highlight) {
        highlights = highlights.map { if (it.id == u.id) u.copy(conversationId = it.conversationId, conversation = it.conversation) else it }
    }

    LaunchedEffect(index, summary) {
        loading = true; error = null
        val rec = positions.get(bookId)
        val same = rec?.chapterSlug == chapter.slug
        place[0] = if (same) rec!!.fullChar else 0
        place[1] = if (same) rec!!.summaryPara else 0
        val cached = if (summary) cache.summary(chapter.slug) else cache.text(chapter.slug)
        text = cached ?: try {
            if (summary) {
                val s = calibre.chapterSummary(bookId, chapter.slug)
                if (!s.exists) error = strings("carnet.chapter.no_summary")
                s.summary.orEmpty().also { if (s.exists && it.isNotEmpty()) cache.keepSummary(chapter.slug, it) }
            } else calibre.chapter(bookId, chapter.slug).text.also { cache.keepText(chapter.slug, it) }
        } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            error = strings("carnet.chapter.load_failed", CalibreApi.error(e)); loading = false; return@LaunchedEffect
        }
        restore = if (summary) charOffsetOfParagraph(text, place[1]) else place[0]
        loading = false
        persist()
        // Secondary: they must not hold the text back.
        highlights = runCatching { calibre.highlights(bookId) }.getOrDefault(highlights)
        runCatching { calibre.bookmarks(bookId) }.getOrNull()?.let { marks ->
            val mark = marks.firstOrNull { it.chapterSlug == chapter.slug && it.view == view }
            bookmarkId = mark?.id; bookmarkNote = mark?.note.orEmpty()
        }
    }

    // The text runs the whole height of the screen and scrolls beneath the
    // bars, as in the article reader: the controls float at the top, the
    // panel at the bottom, and both step aside while the reader reads on,
    // back on a chapter or a view changed.
    var chromeHidden by remember { mutableStateOf(false) }
    LaunchedEffect(index, summary) { chromeHidden = false }
    val density = androidx.compose.ui.platform.LocalDensity.current
    var topHeight by remember { mutableIntStateOf(0) }
    var bottomHeight by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            val clear = Modifier.padding(top = with(density) { topHeight.toDp() }, bottom = with(density) { bottomHeight.toDp() })
            when {
                loading -> Box(clear) { GridLoading() }
                error != null -> Box(clear) { GridMessage(error!!) }
                else -> HighlightableText(
                    text = text, fontSize = READER_SIZES[size], highlights = shown, initialChar = restore,
                    // The chapter's heading: the first thing in the text's own flow, not a band pinned above it.
                    header = {
                        Column(Modifier.padding(bottom = 16.dp)) {
                            ReaderHeading("$title${byline?.let { " · $it" }.orEmpty()}", chapter.title,
                                L("carnet.minutes", chapter.readingMinutes) + if (shown.isEmpty()) "" else " · " + highlightCount(shown.size),
                                titleLines = 6, inset = 0.dp)
                            GridRule()
                        }
                    },
                    topInset = with(density) { topHeight.toDp() },
                    bottomInset = with(density) { bottomHeight.toDp() } + 28.dp,
                    onTopChar = { c ->
                        // Saved only when the place moved: the text laid out at the restored
                        // place reports it once, and that visit is already recorded (as iOS).
                        val i = if (summary) 1 else 0
                        val at = if (summary) paragraphOfChar(text, c) else c
                        if (place[i] != at) { place[i] = at; schedule() }
                    },
                    onChromeVisible = { chromeHidden = !it },
                    onCreate = { s, e, q -> editing = HighlightEdit.New(s, e, q) },
                    onTapHighlight = { editing = HighlightEdit.Existing(it) },
                )
            }
        }
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topHeight = it.height }.readerChrome(chromeHidden, top = true)
                .background(theme.bg).statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GridIconButton(Icons.AutoMirrored.Filled.ArrowBack, L("carnet.back")) { flush(); nav.pop() }
            Spacer(Modifier.weight(1f))
            ReaderSizeButton { size = it }
            Spacer(Modifier.width(8.dp))
            GridIconButton(DomainGlyphs.of(if (bookmarkId != null) "bookmark.fill" else "bookmark"), if (bookmarkId != null) L("carnet.bookmark.remove") else L("carnet.bookmark.add"), on = bookmarkId != null) {
                val (s, v) = chapter.slug to view
                scope.launch {
                    runCatching { calibre.toggleBookmark(bookId, s, v) }.getOrNull()?.let { r ->
                        bookmarkId = if (r.bookmarked) r.bookmark?.id else null
                        bookmarkNote = r.bookmark?.note.orEmpty()
                        if (r.bookmarked) notingBookmark = true
                    }
                }
            }
        }
        // Where you are; text or summary; the chapter; a note.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomHeight = it.height }.readerChrome(chromeHidden, top = false)
                .background(theme.surface).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row {
                    GridLabel("${index + 1} / ${chapters.size} · ${chapter.title}", 9.5.sp, modifier = Modifier.weight(1f))
                    GridLabel("${(progress * 100).roundToInt()} %", 9.5.sp)
                }
                GridBar(progress, accentWhenFull = false)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReaderModeSwitch(summary, chapter.hasSummary, Modifier.weight(1f)) { if (it != summary) { flush(); summary = it } }
                NavArrow("chevron.left", L("carnet.chapter.previous"), index > 0) { go(-1) }
                NavArrow("chevron.right", L("carnet.chapter.next"), index < chapters.size - 1) { go(1) }
                GridButton(L("share.note"), symbol = "pencil", primary = true, small = true) { takingNote = true }
                // Beside the pencil: this chapter talked over with Maurice, in his app.
                if (talk.maurice.installed) MauriceMark(
                    36.dp,
                    Modifier.clip(CircleShape).clickable(enabled = !talk.opening) { talkToMaurice() }.accessibilityLabel(L("carnet.reader.talk_chapter")),
                )
            }
        }
        ReaderTalkOverlay(talk)
    }

    editing?.let { edit ->
        HighlightEditFor(edit, onDismiss = { editing = null },
            onCreate = { n, color, note ->
                val (s, v) = chapter.slug to view
                scope.launch { runCatching { calibre.createHighlight(bookId, s, n.quote, color, note, n.start, n.end, v) }.getOrNull()?.let { highlights = highlights + it } }
            },
            onUpdate = { h, color, note -> scope.launch { runCatching { calibre.updateHighlight(bookId, h.id, note, color) }.getOrNull()?.let(::updated) } },
            onDelete = { h -> scope.launch { runCatching { calibre.deleteHighlight(bookId, h.id) }.onSuccess { highlights = highlights.filterNot { it.id == h.id } } } },
            // A book of the library: the server matches its title to the book's fiche.
            resonance = eu.chezmaurice.core.carnet.ResonanceSource(title, bookTitle = title),
            onLink = { target, note, quote, source -> scope.launch { runCatching { resonances?.create(target, note, quote, source) } } },
            // The passage is saved first; the conversation is then held on it.
            onTalkNew = { n, color, note ->
                val (s, v) = chapter.slug to view
                talk.open(start = {
                    val h = runCatching { calibre.createHighlight(bookId, s, n.quote, color, note, n.start, n.end, v) }.getOrNull()
                        ?: throw ApiError(0, strings("carnet.highlight.save_failed"))
                    highlights = highlights + h
                    garden.startBookConversation(bookId, s, h.id).also { attach(h.id, it) }
                }, opened = { flush() })
            },
            onTalkExisting = { h, color, note ->
                if (color != h.color || note != h.note.orEmpty()) scope.launch { runCatching { calibre.updateHighlight(bookId, h.id, note, color) }.getOrNull()?.let(::updated) }
                talkToMaurice(h.id)
            },
        )
    }
    if (takingNote) NoteSheet(title, offersPage = false, chapterTitle = chapter.title, onDismiss = { takingNote = false }) { t, q, _ ->
        garden.addBookNote(bookId, t, q, chapter.title)
    }
    if (notingBookmark) GridSheet(onDismiss = { notingBookmark = false }) {
        GridLabel(L("carnet.bookmark.note", chapter.title))
        BasicTextField(
            bookmarkNote, { bookmarkNote = it }, textStyle = TextStyle(fontSize = 15.sp, color = theme.ink), cursorBrush = SolidColor(theme.ink),
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(10.dp)).background(theme.bg).border(0.5.dp, theme.ruleHard, RoundedCornerShape(10.dp)).padding(10.dp),
        )
        Row {
            Spacer(Modifier.weight(1f))
            GridButton(L("common.cancel"), small = true) { notingBookmark = false }
            Spacer(Modifier.width(8.dp))
            GridButton(L("note.edit.save"), primary = true, small = true) {
                notingBookmark = false
                val (id, note) = bookmarkId to bookmarkNote.trim()
                if (id != null) scope.launch { runCatching { calibre.setBookmarkNote(bookId, id, note.ifEmpty { null }) } }
            }
        }
    }
}

@Composable
private fun NavArrow(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Box(Modifier.size(32.dp, 40.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(DomainGlyphs.of(symbol), description, tint = if (enabled) theme.ink else theme.inkMute.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
    }
}
