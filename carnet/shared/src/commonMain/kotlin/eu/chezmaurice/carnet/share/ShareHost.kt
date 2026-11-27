package eu.chezmaurice.carnet.share

import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalStrings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.chezmaurice.carnet.Navigator
import eu.chezmaurice.carnet.ui.GridButton
import eu.chezmaurice.carnet.ui.GridLabel
import eu.chezmaurice.carnet.ui.GridSheet
import eu.chezmaurice.carnet.ui.LocalApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import eu.chezmaurice.carnet.reading.formatSize
import eu.chezmaurice.core.carnet.CalibreApi
import eu.chezmaurice.core.carnet.isLibraryBook
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.carnet.DomainChoice
import eu.chezmaurice.core.carnet.GardenApi
import eu.chezmaurice.core.carnet.Outbox
import eu.chezmaurice.core.carnet.SavedArticle
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.wordmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** A page shared to Carnet by another app: its address, and its title when the app gave one. */
data class SharedPage(val url: String, val title: String?)

/** What another app shared, waiting for a member to take it. */
object CarnetShare {
    private val _pending = MutableStateFlow<SharedPage?>(null)
    val pending = _pending.asStateFlow()
    /** The first web address in what was shared; nothing when there is none. */
    fun offer(text: String?, subject: String?) {
        val url = text?.let { Regex("""https?://\S+""").find(it)?.value?.trimEnd('.', ',', ')', ']', '"', '\'') } ?: return
        _pending.value = SharedPage(url, subject?.takeIf { it.isNotBlank() && it != url })
    }
    fun take() { _pending.value = null }

    private val _file = MutableStateFlow<SharedFile?>(null)
    val file = _file.asStateFlow()
    /** An EPUB or a PDF, for the household's library; anything else is not Carnet's to keep. */
    fun offerFile(file: SharedFile) { if (isLibraryBook(file.name)) _file.value = file }
    fun takeFile() { _file.value = null }
}

/** A file shared to Carnet by another app: its name (with its extension), its size if known, and how to read it. */
class SharedFile(val name: String, val size: Long?, val read: suspend () -> ByteArray)

/**
 * Keeping a shared page (`ShareViewController`): the address, a note if the
 * member wants one, Save. It goes into the member's outbox first and leaves
 * it once the household took it, so a page shared offline is not lost; the
 * outbox is sent again at each return to the app.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun ShareHost(nav: Navigator, session: Session) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val api = LocalApi.current ?: return
    val garden = remember(api) { GardenApi(api) }
    val outbox = remember(nav) { memberOutbox(nav.scope) }
    val scope = rememberCoroutineScope()
    val shared by CarnetShare.pending.collectAsState()
    var note by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var filed by remember { mutableStateOf<SavedArticle?>(null) }
    var foldOpen by remember(shared) { mutableStateOf(false) }
    val sharedFile by CarnetShare.file.collectAsState()
    var sendingBook by remember { mutableStateOf(false) }
    val calibre = remember(api) { CalibreApi(api) }
    val language = eu.chezmaurice.maurice.consent.LocalAppLanguage.current

    // Every member's captures on this device, each sent as its member (not only the one signed in).
    suspend fun flush() {
        if (flushOutboxes(session) > 0) nav.shelfVersion++
    }
    LaunchedEffect(outbox) { flush() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, outbox) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) scope.launch { flush() } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val page = shared
    if (page != null) GridSheet(onDismiss = { if (!saving) { CarnetShare.take(); note = null } }) {
        GridLabel(L("share.title"))
        Text(page.title ?: page.url, style = wordmark(20.sp), color = theme.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (page.title != null) Text(page.url, fontSize = 12.sp, color = theme.inkMute, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Why keep this: the question above a fold that holds the note — closed, its first line or « Optional »; open, the field and the microphone.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(L("share.why"), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = theme.inkSoft)
            val n = note
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(theme.bg).border(0.5.dp, theme.ruleHard, RoundedCornerShape(14.dp))) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { foldOpen = !foldOpen; if (foldOpen && n == null) note = "" }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(foldSummary(n, strings), fontSize = 15.sp, color = theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(if (foldOpen) "⌄" else "›", fontSize = 15.sp, color = theme.inkMute)
                }
                if (foldOpen) Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                    val text = n.orEmpty()
                    BasicTextField(
                        text, { note = it }, textStyle = TextStyle(fontSize = 15.sp, color = theme.ink), cursorBrush = SolidColor(theme.ink),
                        modifier = Modifier.weight(1f).heightIn(min = 96.dp).padding(4.dp),
                        decorationBox = { inner -> if (text.isEmpty()) Text(L("share.note"), fontSize = 15.sp, color = theme.inkMute); inner() },
                    )
                    eu.chezmaurice.carnet.ui.DictationMic(L("share.dictate")) { words -> val before = note.orEmpty().trim(); note = if (before.isEmpty()) words else "$before $words" }
                }
            }
        }
        Row {
            Spacer(Modifier.weight(1f))
            GridButton(L("common.cancel"), small = true, enabled = !saving) { CarnetShare.take(); note = null }
            Spacer(Modifier.width(8.dp))
            GridButton(if (saving) L("share.saving") else L("note.edit.save"), primary = true, small = true, enabled = !saving, description = L("share.save")) {
                val lang = Locale.current.language.takeIf { it == "fr" || it == "en" } ?: "en"
                val item = Outbox.Item(Random.nextLong().toULong().toString(16), page.url, page.title, note?.trim()?.ifEmpty { null }, lang, Clock.System.now().toEpochMilliseconds())
                outbox.add(item)
                saving = true
                scope.launch {
                    notice = try {
                        val saved = garden.saveArticle(item.url, item.locale, item.comment, item.title)
                        outbox.remove(item.id); nav.shelfVersion++
                        // Where the household filed it, with Change — for a member who has domains.
                        if (saved.domainChoices.isNotEmpty() && !saved.needsCapture && saved.slug.isNotEmpty()) filed = saved
                        null
                    } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                        if (Outbox.refusedForGood(e)) { outbox.remove(item.id); strings("carnet.share.refused", e.message.orEmpty()) }
                        else strings("share.queued")
                    }
                    saving = false; note = null; CarnetShare.take()
                }
            }
        }
    }
    // An EPUB or a PDF: to the household's library, waiting on its answer.
    // Kept in the member's book outbox until the library took it; a refusal
    // is said and not kept, a library or network that did not answer leaves
    // it for Carnet to send again.
    sharedFile?.let { file ->
        GridSheet(onDismiss = { if (!sendingBook) CarnetShare.takeFile() }) {
            GridLabel(L("share.title"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(eu.chezmaurice.ui.DomainGlyphs.of("book.closed"), null, tint = theme.inkSoft, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, style = wordmark(20.sp), color = theme.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    file.size?.let { Text(formatSize(it, language), fontSize = 12.sp, color = theme.inkMute) }
                }
            }
            if (sendingBook) Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = theme.inkMute)
                Spacer(Modifier.width(8.dp))
                Text(L("share.adding_book"), fontSize = 14.sp, color = theme.inkSoft)
            }
            Row {
                Spacer(Modifier.weight(1f))
                GridButton(L("common.cancel"), small = true, enabled = !sendingBook) { CarnetShare.takeFile() }
                Spacer(Modifier.width(8.dp))
                GridButton(L("share.add_book"), primary = true, small = true, enabled = !sendingBook) {
                    sendingBook = true
                    scope.launch {
                        val books = memberBookOutbox(nav.scope)
                        val item = BookOutbox.Item(Random.nextLong().toULong().toString(16), file.name, Clock.System.now().toEpochMilliseconds())
                        notice = try {
                            val bytes = file.read()
                            books.add(item, bytes)
                            val added = calibre.addBook(bytes, file.name)
                            books.remove(item.id); nav.shelfVersion++
                            strings(if (added.alreadyThere) "share.book_already" else "share.book_added")
                        } catch (e: CancellationException) { throw e } catch (e: ApiError) {
                            // The library said no (not a book, no library here): sending it again would say the same.
                            if (e.status != 401) books.remove(item.id)
                            e.message
                        } catch (e: Throwable) {
                            if (books.items().none { it.id == item.id }) e.message ?: strings("share.nothing") else strings("share.queued")
                        }
                        sendingBook = false; CarnetShare.takeFile()
                    }
                }
            }
        }
    }
    notice?.let { text ->
        GridSheet(onDismiss = { notice = null }) {
            Text(text, fontSize = 14.sp, color = theme.ink)
            Row { Spacer(Modifier.weight(1f)); GridButton(L("common.ok"), primary = true, small = true) { notice = null } }
        }
    }
    filed?.let { saved -> FiledSheet(saved, garden) { filed = null } }
}

/** Under a closed fold: the note's first line, or « Optional » when there is none. */
private fun foldSummary(note: String?, strings: eu.chezmaurice.ui.Strings): String {
    note?.trim()?.lineSequence()?.firstOrNull { it.isNotEmpty() }?.let { return it }
    return strings("share.optional").replaceFirstChar { it.uppercase() }
}

/**
 * Saved, and where it went: no tags to type, the household filed the
 * article under one of the member's domains, or none. Change offers the
 * others and keeps the sheet open; left alone, it goes after a few seconds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FiledSheet(saved: SavedArticle, garden: GardenApi, onClose: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var domain by remember(saved) { mutableStateOf(saved.domain) }
    var choosing by remember(saved) { mutableStateOf(false) }
    var error by remember(saved) { mutableStateOf<String?>(null) }
    LaunchedEffect(saved, choosing) {
        if (!choosing) { delay(3_000); onClose() }
    }
    fun choose(choice: DomainChoice?) {
        error = null
        scope.launch {
            try {
                domain = garden.setArticleDomain(saved.slug, saved.locale, choice?.id)
                choosing = false
                delay(900); onClose()
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                error = strings("share.change_failed")
            }
        }
    }
    GridSheet(onDismiss = onClose) {
        GridLabel(if (saved.duplicate) L("share.duplicate") else L("share.saved"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val name = domain?.name
            Text(
                if (name != null) buildAnnotatedString { append(L("share.filed_in") + " "); withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(name) } }
                else AnnotatedString(L("share.filed_nowhere")),
                fontSize = 15.sp, color = theme.ink, modifier = Modifier.weight(1f),
            )
            if (!choosing) Text(
                if (name != null) L("share.change") else L("share.choose"), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = theme.ink,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { choosing = true }.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        if (choosing) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            saved.domainChoices.forEach { choice -> DomainChip(choice.name, on = choice.id == domain?.id) { choose(choice) } }
            DomainChip(L("share.no_domain"), on = false) { choose(null) }
        }
        error?.let { Text(it, fontSize = 13.sp, color = theme.inkMute) }
        if (choosing) Row { Spacer(Modifier.weight(1f)); GridButton(L("common.done"), primary = true, small = true, onClick = onClose) }
    }
}

@Composable
private fun DomainChip(label: String, on: Boolean, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Text(
        label, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        color = if (on) theme.bg else theme.ink,
        modifier = Modifier.height(34.dp).clip(CircleShape).background(if (on) theme.ink else theme.bg)
            .border(0.5.dp, if (on) theme.ink else theme.ruleHard, CircleShape).clickable(onClick = onClick)
            .padding(horizontal = 14.dp).wrapContentHeight(Alignment.CenterVertically),
    )
}
