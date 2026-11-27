package eu.chezmaurice.carnet.shelf

import androidx.compose.ui.semantics.Role
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.carnet.ui.GridButton
import eu.chezmaurice.carnet.ui.GridLabel
import eu.chezmaurice.carnet.ui.minePaper
import eu.chezmaurice.core.carnet.CalibreApi
import eu.chezmaurice.core.carnet.EntrySuggestion
import eu.chezmaurice.core.carnet.LibraryAddResult
import eu.chezmaurice.core.carnet.SuggestionList
import eu.chezmaurice.core.carnet.SuggestionsApi
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.maurice.chat.SuggestionRow
import eu.chezmaurice.maurice.consent.LocalAppLanguage
import eu.chezmaurice.maurice.platform.rememberFilePicker
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * What the shelf's « + » does, apart from the screen (`AddEntrySheet`): the
 * member's words looked up as a conversation's subjects are, with no
 * conversation (`POST /api/suggestions/from-text`); a row kept, its entry;
 * an EPUB or a PDF to the household's library (`POST /api/v1/calibre/add`).
 */
class AddEntry(api: ApiClient) {
    val suggestions = SuggestionsApi(api)
    private val calibre = CalibreApi(api)

    /** The rows found for [words], with the batch to read them again by. */
    suspend fun ask(words: String, locale: String?): SuggestionList = worded { suggestions.fromText(words, locale) }

    /** Kept: the entry to open (`<collection>/<locale>/<slug>`), or null when the list is to be read again. */
    suspend fun keep(row: EntrySuggestion, candidate: String?, episodes: List<String>): String? =
        worded { suggestions.keep(row.id, candidate, episodes)?.entry }

    /** Another look; the number found. */
    suspend fun search(row: EntrySuggestion, query: String?): Int = worded { suggestions.search(row.id, query) }

    /** A refusal as the suggestions' routes word it on iOS (`MauriceAPI.message`): the server's `error`, else "HTTP <status>". */
    private suspend fun <T> worded(call: suspend () -> T): T = try { call() } catch (e: ApiError) {
        throw if (e.fromServer || e.status == 0) e else ApiError(e.status, "HTTP ${e.status}")
    }

    /** The list read again; null when it could not be. */
    suspend fun reread(batch: String?): List<EntrySuggestion>? =
        batch?.let { runCatching { suggestions.batch(it).suggestions }.getOrNull() }

    /** Sent to the library: the book to open (`calibre/<id>`), else what the library said. */
    suspend fun addFile(bytes: ByteArray, filename: String): Pair<String?, LibraryAddResult> =
        calibre.addBook(bytes, filename).let { r -> r.bookId?.let { "calibre/$it" } to r }
}

/** The shelf's floating « + », bottom right. */
@Composable
fun AddEntryButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Box(
        modifier.size(56.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(theme.ink).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Add, L("add.button"), tint = theme.bg, modifier = Modifier.size(26.dp))
    }
}

/**
 * The shelf's « + »: a new entry without a conversation. The member says in
 * their own words what they want to add — a work, a person — and gets back
 * what Maurice's conversations offer: the same cards (keep, pick the right
 * one, search again, refine, dismiss). A row kept opens its entry. Under the
 * question, a book from the device's files goes to the household's library
 * and its book opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEntrySheet(api: ApiClient, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val add = remember(api) { AddEntry(api) }
    val scope = rememberCoroutineScope()
    val language = LocalAppLanguage.current
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf("") }
    var batch by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<EntrySuggestion>>(emptyList()) }
    // A search was made: an empty list then says nothing was found.
    var asked by remember { mutableStateOf(false) }
    var looking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sendingFile by remember { mutableStateOf(false) }
    // What the library said, when there is no book to open.
    var fileNote by remember { mutableStateOf<String?>(null) }
    val pending = rows.filterNot { it.isKept }
    val canAsk = !looking && text.isNotBlank()
    val strings = eu.chezmaurice.ui.LocalStrings.current

    fun open(ref: String) { onDismiss(); onOpen(ref) }
    fun ask() {
        val words = text.trim()
        if (words.isEmpty() || looking) return
        focusManager.clearFocus()
        looking = true; error = null
        scope.launch {
            try { add.ask(words, language).let { batch = it.batch; rows = it.suggestions } }
            catch (e: CancellationException) { throw e } catch (e: Throwable) { rows = emptyList(); error = e.message }
            looking = false; asked = true
        }
    }
    suspend fun reread() { add.reread(batch)?.let { rows = it } }
    val pick = rememberFilePicker(multiple = false, mimeTypes = listOf("application/epub+zip", "application/pdf")) { files ->
        val file = files.firstOrNull() ?: return@rememberFilePicker
        sendingFile = true; error = null
        scope.launch {
            try {
                val (ref, result) = add.addFile(file.open().toByteArray(), file.name)
                if (ref != null) open(ref) else fileNote = strings(if (result.alreadyThere) "add.file_exists" else "add.file_added")
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = e.message }
            sendingFile = false
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = theme.bg, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(L("common.close"), color = theme.ink) }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // The bubble: the question, and the member's words.
                Column(
                    Modifier.fillMaxWidth().minePaper(theme, 14.dp).border(0.5.dp, theme.ruleHard, RoundedCornerShape(14.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(L("add.title"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = theme.ink)
                    Row(verticalAlignment = Alignment.Bottom) {
                        val style = TextStyle(fontSize = 15.sp, color = theme.ink)
                        BasicTextField(
                            text, { v -> if (v.contains('\n')) { text = v.replace("\n", ""); ask() } else text = v },
                            textStyle = style, cursorBrush = SolidColor(theme.ink), maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { ask() }),
                            modifier = Modifier.weight(1f).heightIn(min = 34.dp).focusRequester(focus),
                            decorationBox = { inner ->
                                Box(Modifier.heightIn(min = 34.dp), contentAlignment = Alignment.CenterStart) {
                                    if (text.isEmpty()) Text(L("add.placeholder"), style = style.copy(color = theme.inkMute))
                                    inner()
                                }
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(if (canAsk) theme.ink else theme.surface)
                                .border(0.5.dp, if (canAsk) theme.ink else theme.ruleHard, CircleShape).clickable(enabled = canAsk, role = Role.Button) { ask() },
                            contentAlignment = Alignment.Center,
                        ) { Icon(eu.chezmaurice.ui.DomainGlyphs.of("arrow.up"), L("add.go"), tint = if (canAsk) theme.bg else theme.inkMute, modifier = Modifier.size(18.dp)) }
                    }
                }
                // The other way in: a book the member has as a file.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GridButton(L("add.file"), symbol = "doc.badge.plus", small = true, enabled = !sendingFile && !looking) {
                        focusManager.clearFocus(); fileNote = null; pick()
                    }
                    if (sendingFile) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = theme.inkMute)
                        Spacer(Modifier.width(8.dp))
                        GridLabel(L("add.file_sending"), 9.5.sp)
                    }
                }
                fileNote?.let { Text(it, fontSize = 13.sp, color = theme.inkSoft) }
                if (looking) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = theme.inkMute)
                    Spacer(Modifier.width(8.dp))
                    GridLabel(L("add.looking"), 9.5.sp)
                }
                error?.let { Text(it, fontSize = 13.sp, color = Color(0xFFC0392B)) }
                if (pending.isNotEmpty()) {
                    GridLabel(L("add.suggestions"), 9.5.sp, color = theme.inkSoft)
                    for (row in pending) androidx.compose.runtime.key(row.id) {
                        SuggestionRow(
                            row, api.baseUrl,
                            onKeep = { candidate, episodes ->
                                try {
                                    val ref = add.keep(row, candidate, episodes)
                                    if (ref != null) open(ref) else reread()
                                    true
                                } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = e.message; false }
                            },
                            onEpisodes = { feed -> add.suggestions.episodes(row.id, feed) },
                            onSearch = { query ->
                                try { add.search(row, query).also { reread() } }
                                catch (e: CancellationException) { throw e } catch (e: Throwable) { error = e.message; null }
                            },
                            onDismiss = {
                                rows = rows.filterNot { it.id == row.id }
                                scope.launch { runCatching { add.suggestions.dismiss(row.id) } }
                            },
                            ownWords = true,
                        )
                    }
                } else if (asked && !looking && error == null) {
                    Text(L("add.none"), fontSize = 13.5.sp, color = theme.inkSoft)
                }
            }
        }
    }
}
