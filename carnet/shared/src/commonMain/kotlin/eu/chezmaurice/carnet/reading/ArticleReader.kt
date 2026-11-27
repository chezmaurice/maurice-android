package eu.chezmaurice.carnet.reading

import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.carnet.ui.highlightCount
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import eu.chezmaurice.carnet.Navigator
import eu.chezmaurice.carnet.entry.NoteSheet
import eu.chezmaurice.carnet.entry.WritePanel
import eu.chezmaurice.carnet.entry.rememberAsk
import eu.chezmaurice.carnet.ui.GridIconButton
import eu.chezmaurice.carnet.ui.GridLoading
import eu.chezmaurice.carnet.ui.GridMessage
import eu.chezmaurice.carnet.ui.GridRule
import eu.chezmaurice.carnet.ui.LocalApi
import eu.chezmaurice.carnet.ui.PAGE
import eu.chezmaurice.core.carnet.Article
import eu.chezmaurice.core.carnet.CalibreApi
import eu.chezmaurice.core.carnet.GardenApi
import eu.chezmaurice.core.carnet.Highlight
import eu.chezmaurice.core.carnet.HighlightConversation
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.carnet.Positions
import eu.chezmaurice.core.carnet.ShelfEntry
import eu.chezmaurice.core.carnet.TextStyle
import eu.chezmaurice.ui.LocalMauriceTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * An article's captured text (`ArticleReadView` + `ArticleReaderView`): the
 * same surface as a chapter — text or summary, three sizes, highlights, the
 * place kept — with the article's heading in the text's own flow, and at
 * the bottom, a note or Maurice. Its place stays on the device.
 *
 * [shared]: an article given from another household and shared live with the
 * member, read from its shelf row in its owner's notes (`?owner=`), with no
 * highlights of the member's (the server keeps those on the member's own fiche).
 */
@OptIn(ExperimentalTime::class)
@Composable
fun ArticleReader(nav: Navigator, slug: String, locale: String, shared: ShelfEntry? = null) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val api = LocalApi.current ?: return
    val calibre = remember(api) { CalibreApi(api) }
    val resonances = remember(api) { eu.chezmaurice.core.carnet.ResonancesApi(api) }
    val garden = remember(api) { GardenApi(api) }
    val scope = rememberCoroutineScope()
    var article by remember { mutableStateOf<Article?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf(false) }
    var size by remember { mutableIntStateOf(ReaderSize.get()) }
    var text by remember { mutableStateOf("") }
    var styles by remember { mutableStateOf<List<TextStyle>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var highlights by remember { mutableStateOf<List<Highlight>>(emptyList()) }
    var entry by remember { mutableStateOf(shared) }
    var editing by remember { mutableStateOf<HighlightEdit?>(null) }
    var noting by remember { mutableStateOf(false) }
    var restore by remember { mutableIntStateOf(0) }
    val place = remember { intArrayOf(0, 0) }
    var saving by remember { mutableStateOf<Job?>(null) }
    val owner = shared?.owner?.username
    // Apart from the member's own article at the same slug.
    val key = "article:" + (owner?.let { "@$it/" } ?: "") + "$locale/$slug"
    val ask = rememberAsk(nav, entry)

    fun persist() {
        if (article == null) return
        nav.positions.save(key, Positions.Record(0, slug, if (summary) "summary" else "full", place[0], place[1], Clock.System.now().toEpochMilliseconds()))
    }
    fun schedule() { saving?.cancel(); saving = scope.launch { delay(1_000); persist() } }
    DisposableEffect(Unit) { onDispose { saving?.cancel(); persist() } }

    // A passage talked over with Maurice: only on the member's own article
    // (one read in someone else's notes has no highlights of theirs).
    val talk = rememberReaderTalk()
    fun attach(highlightId: Int, conversationId: String) {
        highlights = highlights.map { if (it.id == highlightId) it.copy(conversationId = conversationId, conversation = HighlightConversation(conversationId)) else it }
    }
    fun talkToMaurice(highlightId: Int) = talk.open(start = {
        garden.startArticleConversation(locale, slug, highlightId).also { attach(highlightId, it) }
    }, opened = { saving?.cancel(); persist() })
    /** An update answers without the passage's conversation: keep the one listed. */
    fun updated(u: Highlight) {
        highlights = highlights.map { if (it.id == u.id) u.copy(conversationId = it.conversationId, conversation = it.conversation) else it }
    }

    LaunchedEffect(Unit) {
        // The article's own page is its owner's: the shelf row stands for it.
        if (shared != null) { article = Article.shared(shared); return@LaunchedEffect }
        try { article = calibre.article(slug, locale) } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            failure = strings("carnet.article.load_failed", CalibreApi.error(e))
        }
    }

    LaunchedEffect(article, summary) {
        val a = article ?: return@LaunchedEffect
        loading = true; error = null
        // Character offsets in both views: articles have no server position to translate for.
        val rec = nav.positions.get(key)
        place[0] = rec?.fullChar ?: 0; place[1] = rec?.summaryPara ?: 0
        restore = if (summary) place[1] else place[0]
        val cache = ArticleCache(nav.scope, a)
        if (summary) {
            text = a.readableSummary; styles = emptyList()
            if (text.isEmpty()) error = strings("carnet.article.no_summary")
        } else {
            val kept = cache.text()
            if (kept != null) { text = kept; styles = cache.styles() }
            else try {
                val full = calibre.articleText(slug, locale, owner)
                text = full.text; styles = full.styles.orEmpty()
                cache.keep(text, styles)
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                error = strings("carnet.article.load_failed", CalibreApi.error(e)); loading = false; return@LaunchedEffect
            }
        }
        loading = false
        persist()
        if (entry == null) entry = runCatching { garden.entry("articles", locale, slug).entry }.getOrNull()
        // Highlights are kept against the member's own fiche: an article read
        // in someone else's notes has none to show or keep.
        if (owner != null) return@LaunchedEffect
        highlights = runCatching { calibre.articleHighlights(slug, locale) }.getOrDefault(highlights)
    }

    val view = if (summary) "summary" else "full"
    val shown = highlights.filter { it.readerView == view }

    // The bars float over the text and step aside while the reader reads on;
    // back at the top, at the end, and when the view changes.
    var chromeHidden by remember { mutableStateOf(false) }
    LaunchedEffect(summary) { chromeHidden = false }
    val density = androidx.compose.ui.platform.LocalDensity.current
    var topHeight by remember { mutableIntStateOf(0) }
    var bottomHeight by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            val a = article
            val clear = Modifier.padding(top = with(density) { topHeight.toDp() }, bottom = with(density) { bottomHeight.toDp() })
            when {
                failure != null -> Box(clear) { GridMessage(failure!!) }
                a == null || loading -> Box(clear) { GridLoading() }
                error != null -> Box(clear) { GridMessage(error!!) }
                else -> HighlightableText(
                    topInset = with(density) { topHeight.toDp() },
                    bottomInset = with(density) { bottomHeight.toDp() } + 28.dp,
                    onChromeVisible = { chromeHidden = !it },
                    text = text, fontSize = READER_SIZES[size], highlights = shown, styles = styles, initialChar = restore,
                    header = {
                        Column(Modifier.padding(bottom = 16.dp)) {
                            val n = shown.size
                            ReaderHeading(a.bylineShort.ifEmpty { L("suggest.kind.articles") }, a.title, L("carnet.minutes", a.readingMinutes) + if (n == 0) "" else " · " + highlightCount(n), titleLines = 8, inset = 0.dp)
                            GridRule()
                        }
                    },
                    onTopChar = { c -> if (summary) place[1] = c else place[0] = c; schedule() },
                    onCreate = { s, e, q -> if (owner == null) editing = HighlightEdit.New(s, e, q) },
                    onTapHighlight = { editing = HighlightEdit.Existing(it) },
                )
            }
        }
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topHeight = it.height }.readerChrome(chromeHidden, top = true)
                .background(theme.bg).statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GridIconButton(Icons.AutoMirrored.Filled.ArrowBack, L("carnet.back")) { saving?.cancel(); persist(); nav.pop() }
            Spacer(Modifier.weight(1f))
            article?.let { a -> ReaderModeSwitch(summary, a.hasSummary, Modifier.widthIn(max = 230.dp).weight(3f)) { if (it != summary) { saving?.cancel(); persist(); summary = it } } }
            Spacer(Modifier.weight(1f))
            ReaderSizeButton { size = it }
        }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomHeight = it.height }.readerChrome(chromeHidden, top = false)
                .background(theme.surface).navigationBarsPadding().padding(horizontal = PAGE),
        ) {
            WritePanel(onNote = { noting = true }, onAsk = if (entry == null) null else ask)
        }
        ReaderTalkOverlay(talk)
    }

    editing?.let { edit ->
        HighlightEditFor(edit, onDismiss = { editing = null },
            onCreate = { n, color, note -> scope.launch { runCatching { calibre.createArticleHighlight(slug, locale, n.quote, color, note, n.start, n.end, view) }.getOrNull()?.let { highlights = highlights + it } } },
            onUpdate = { h, color, note -> scope.launch { runCatching { calibre.updateArticleHighlight(slug, h.id, note, color) }.getOrNull()?.let(::updated) } },
            onDelete = { h -> scope.launch { runCatching { calibre.deleteArticleHighlight(slug, h.id) }.onSuccess { highlights = highlights.filterNot { it.id == h.id } } } },
            // An article lives in the garden: the résonance links back to its fiche.
            resonance = article?.let { eu.chezmaurice.core.carnet.ResonanceSource(it.title, basename = "$slug-fiche") },
            onLink = { target, note, quote, source -> scope.launch { runCatching { resonances.create(target, note, quote, source) } } },
            // Saved first, then talked over; never on an article read in someone else's notes.
            onTalkNew = if (owner != null) null else { n, color, note ->
                talk.open(start = {
                    val h = runCatching { calibre.createArticleHighlight(slug, locale, n.quote, color, note, n.start, n.end, view) }.getOrNull()
                        ?: throw ApiError(0, strings("carnet.highlight.save_failed"))
                    highlights = highlights + h
                    garden.startArticleConversation(locale, slug, h.id).also { attach(h.id, it) }
                }, opened = { saving?.cancel(); persist() })
            },
            onTalkExisting = if (owner != null) null else { h, color, note ->
                if (color != h.color || note != h.note.orEmpty()) scope.launch { runCatching { calibre.updateArticleHighlight(slug, h.id, note, color) }.getOrNull()?.let(::updated) }
                talkToMaurice(h.id)
            },
        )
    }
    if (noting) NoteSheet(article?.title.orEmpty(), offersPage = true, onDismiss = { noting = false }) { t, q, p ->
        val e = entry ?: garden.entry("articles", locale, slug).entry.also { entry = it }
        entry = garden.addNote(e, t, q, p).entry
    }
}
