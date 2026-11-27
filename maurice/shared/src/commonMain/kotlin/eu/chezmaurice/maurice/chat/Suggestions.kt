package eu.chezmaurice.maurice.chat

import eu.chezmaurice.ui.accessibilityLabel
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.chezmaurice.core.carnet.EntrySuggestion
import eu.chezmaurice.core.chat.ChatController
import eu.chezmaurice.core.model.Conversation
import eu.chezmaurice.maurice.absoluteUrl
import eu.chezmaurice.maurice.resources.Res
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.decodeToImageBitmap

// What a conversation is worth keeping in the member's garden, as the iOS app
// shows it (CarnetSuggestions.swift, specs/carnet-suggestions.md): Carnet's
// pipe in the conversation's header, and behind it the drawer — what waits to
// be kept, and what was. Nothing reaches the garden until a row is kept.

/** What a kind of entry is called, and the glyph that stands in for a cover. */
internal fun kindSymbol(kind: String) = when (kind) {
    "movies" -> "film"
    "series" -> "tv"
    "books" -> "book"
    "music" -> "music.note"
    "podcasts" -> "mic"
    "games" -> "gamecontroller"
    "people" -> "person"
    "articles" -> "doc.text"
    else -> "doc"
}

/**
 * The Carnet mark: absent while the conversation offers nothing, carrying the
 * count of what waits, swelling once when a turn adds an offer, and staying —
 * quiet — as the way back to what was kept. Never in a room, never for a
 * guest (their garden is in another household).
 */
@Composable
fun SuggestionsMark(conversation: Conversation, onOpen: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val pending = conversation.suggestions ?: 0
    val kept = conversation.suggestionsKept ?: 0
    if (pending == 0 && kept == 0 && conversation.entryRef == null) return
    val swell = remember { Animatable(1f) }
    var before by remember(conversation.id) { mutableStateOf(pending) }
    LaunchedEffect(pending) {
        if (pending > before) {
            swell.animateTo(1.3f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
            swell.animateTo(1f, spring(dampingRatio = 0.6f))
        }
        before = pending
    }
    val tint = if (pending > 0) theme.ink else theme.inkSoft
    Row(
        Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpen)
            .accessibilityLabel(if (pending > 0) L("suggest.a11y_pending", pending) else L("suggest.help"))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CarnetPipe(26.dp, tint, Modifier.scale(swell.value))
        if (pending > 0) {
            Spacer(Modifier.width(3.dp))
            Text("$pending", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tint)
        }
    }
}

/** Carnet's pipe, cut from its icon: the two apps go together, and this is where one hands over to the other. */
@Composable
private fun CarnetPipe(size: Dp, tint: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(null) {
        value = runCatching { Res.readBytes("files/emblems/carnet.png").decodeToImageBitmap() }.getOrNull()
    }
    Box(modifier.size(size)) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(tint)) }
    }
}

/** The drawer: what waits to be kept, and what was, for one conversation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionsDrawer(conversationId: String, controller: ChatController, serverUrl: String, onDismiss: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val api = controller.suggestions ?: return
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<EntrySuggestion>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val state by controller.state.collectAsState()
    val counts = state.conversations.firstOrNull { it.id == conversationId }?.let { it.suggestions to it.suggestionsKept }

    // The entry the conversation is held from, when it is: the drawer's first row.
    val held = state.activeEntry?.takeIf { state.activeId == conversationId }

    suspend fun load() {
        try {
            // Once the pass the last reply started is done: what it found is in.
            val list = api.list(conversationId, settle = true)
            rows = list.suggestions
            controller.setSuggestionCounts(conversationId, list.pending, list.kept)
            failed = false
        } catch (e: CancellationException) { throw e } catch (_: Throwable) { failed = true }
        loaded = true
    }
    // A turn that ends while the drawer is open adds its rows to it.
    LaunchedEffect(counts) { load() }

    suspend fun keep(row: EntrySuggestion, candidate: String?, episodes: List<String>): Boolean = try {
        api.keep(row.id, candidate, episodes)
        // Linked to an entry (a shared article, a fiche in the context): the conversation may be held from it now.
        if (row.note.isEmpty()) controller.refreshActiveEntry()
        load(); true
    } catch (e: CancellationException) { throw e } catch (_: Throwable) { failed = true; false }

    suspend fun search(row: EntrySuggestion, query: String?): Int? = try {
        api.search(row.id, query).also { load() }
    } catch (e: CancellationException) { throw e } catch (_: Throwable) { failed = true; null }

    fun dismissRow(row: EntrySuggestion) {
        // Gone at once; the server's answer only confirms it.
        rows = rows.filterNot { it.id == row.id }
        scope.launch {
            try { api.dismiss(row.id) } catch (e: CancellationException) { throw e } catch (_: Throwable) { failed = true }
            load()
        }
    }

    val pending = rows.filterNot { it.isKept }
    // What was kept — but the held entry, which has its own row above.
    val kept = rows.filter { it.isKept && it.entry != held?.ref }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = theme.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(L("suggest.title"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = theme.ink, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(L("common.done"), color = theme.ink) }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                if (held != null) Section(L("suggest.section_entry")) { HeldEntryRow(held, serverUrl) }
                if (pending.isNotEmpty()) Section(L("suggest.section_pending")) {
                    for (row in pending) SuggestionRow(
                        row, serverUrl,
                        onKeep = { candidate, episodes -> keep(row, candidate, episodes) },
                        onEpisodes = { feed -> api.episodes(row.id, feed) },
                        onSearch = { search(row, it) }, onDismiss = { dismissRow(row) },
                    )
                }
                if (kept.isNotEmpty()) Section(L("suggest.section_kept")) {
                    for (row in kept) KeptRow(row, serverUrl)
                }
                if (loaded && rows.isEmpty() && held == null) Text(L("suggest.empty"), fontSize = 14.sp, color = theme.inkMute, modifier = Modifier.fillMaxWidth().padding(top = 32.dp))
                if (failed) Text(L("suggest.failed"), fontSize = 12.sp, color = androidx.compose.ui.graphics.Color(0xFFC0392B))
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val theme = LocalMauriceTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = theme.inkMute)
        content()
    }
}

/** A portrait for a person, a sleeve for an album or a show, a poster otherwise; the kind's glyph without one. */
@Composable
internal fun Cover(image: String?, kind: String, serverUrl: String, width: Dp = 46.dp) {
    val theme = LocalMauriceTheme.current
    val height = if (kind == "music" || kind == "podcasts" || kind == "people") width else width * 3 / 2
    val shape: Shape = if (kind == "people") CircleShape else RoundedCornerShape(5.dp)
    val url = image?.takeIf { it.isNotEmpty() }?.let { absoluteUrl(serverUrl, it) }
    Box(Modifier.width(width).height(height).clip(shape).background(theme.inkMute.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(DomainGlyphs.of(kindSymbol(kind)), null, tint = theme.inkMute, modifier = Modifier.size(width * 0.36f))
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** The kind of entry, written out: two offers can share a title. */
@Composable
private fun KindPill(kind: String, compact: Boolean = false) {
    val theme = LocalMauriceTheme.current
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(theme.inkMute.copy(alpha = 0.12f))
            .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = if (compact) 2.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(DomainGlyphs.of(kindSymbol(kind)), null, tint = theme.inkSoft, modifier = Modifier.size(if (compact) 10.dp else 11.dp))
        Spacer(Modifier.width(4.dp))
        Text(L("suggest.kind.$kind"), fontSize = if (compact) 10.sp else 11.sp, fontWeight = FontWeight.SemiBold, color = theme.inkSoft)
    }
}

private fun identityLine(year: Int?, subtitle: String?) = listOfNotNull(year?.toString(), subtitle?.takeIf { it.isNotEmpty() }).joinToString(" · ")

/**
 * An offer: who or what it is, whether the member already has it, the note
 * that would be filed — in full, so it is read before it is written — and one
 * button. Several possible identities unfold into a pick first; a row that
 * was looked up can be looked up again.
 */
@Composable
fun SuggestionRow(
    row: EntrySuggestion,
    serverUrl: String,
    onKeep: suspend (String?, List<String>) -> Boolean,
    onEpisodes: suspend (String?) -> eu.chezmaurice.core.carnet.EpisodeList?,
    onSearch: suspend (String?) -> Int?,
    onDismiss: () -> Unit,
    /** Asked for in the member's own words (Carnet's « + »): nothing to link, and an entry already theirs opens. */
    ownWords: Boolean = false,
) {
    val theme = LocalMauriceTheme.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    // One possibility is the one: picked at once, so a show lists its episodes without a tap that says nothing.
    var picked by remember(row.candidates.map { it.id }) { mutableStateOf(row.candidates.singleOrNull()?.id) }
    // For a podcast: the episodes ticked, in the order they were — none for the show itself.
    var episodes by remember { mutableStateOf<List<String>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var refining by remember { mutableStateOf(false) }
    var term by remember { mutableStateOf("") }
    var nothing by remember { mutableStateOf<String?>(null) }
    val needsPick = row.candidates.isNotEmpty()
    val canKeep = !needsPick || picked != null
    val strings = eu.chezmaurice.ui.LocalStrings.current

    fun search(query: String?) {
        val typed = query?.trim()
        if (typed != null && typed.isEmpty()) return
        searching = true; nothing = null
        scope.launch {
            val found = onSearch(typed)
            searching = false
            if (found == null) return@launch
            if (found == 0) nothing = typed?.let { strings("suggest.search_nothing", it) } ?: strings("suggest.search_nothing_new")
            else { picked = null; refining = false }
        }
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(theme.bg).border(1.dp, theme.rule, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Cover(row.image, row.kind, serverUrl)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                KindPill(row.kind)
                Text(row.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = theme.ink)
                identityLine(row.year, row.subtitle).takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.sp, color = theme.inkSoft) }
                Text(
                    when { row.existing -> L("suggest.state_existing"); row.inLibrary == true -> L("suggest.state_library"); needsPick -> L("suggest.state_pick", row.candidates.size); else -> L("suggest.state_new") },
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = theme.inkMute,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                Icon(androidx.compose.material.icons.Icons.Default.Close, L("suggest.dismiss"), tint = theme.inkMute, modifier = Modifier.size(14.dp))
            }
        }
        // The note, as it will read on the entry. A shared article has none: keeping it saves the article.
        if (row.note.isNotEmpty()) {
            Text(row.note, fontSize = 14.sp, color = theme.ink, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.inkMute.copy(alpha = 0.07f)).padding(10.dp))
        }
        // Which of these did the conversation mean?
        if (needsPick) Column {
            for (c in row.candidates) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { picked = c.id }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(16.dp).clip(CircleShape).border(1.5.dp, if (picked == c.id) theme.ink else theme.inkMute, CircleShape), contentAlignment = Alignment.Center) {
                    if (picked == c.id) Box(Modifier.size(8.dp).clip(CircleShape).background(theme.ink))
                }
                Spacer(Modifier.width(10.dp))
                Cover(c.image, row.kind, serverUrl, 28.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = theme.ink)
                    identityLine(c.year, c.subtitle).takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 11.sp, color = theme.inkSoft, maxLines = 1) }
                }
            }
        }
        // A show: which episodes? Listed once the show is settled — the row's own, or the candidate picked.
        if (row.kind == "podcasts" && (!needsPick || picked != null)) {
            androidx.compose.runtime.key(row.candidates.joinToString(",") { it.id } + "|" + picked.orEmpty()) {
                EpisodePicker(feed = if (needsPick) picked else null, serverUrl = serverUrl, onEpisodes = onEpisodes, selection = episodes) { episodes = it }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { working = true; scope.launch { onKeep(picked, episodes); working = false } },
                enabled = !working && canKeep,
                colors = ButtonDefaults.buttonColors(containerColor = theme.ink, contentColor = theme.bg),
            ) {
                // No note to file: keeping says the conversation is about it — an entry in the context, a shared article.
                Text(
                    when {
                        episodes.size > 1 -> L("suggest.keep_episodes", episodes.size)
                        episodes.size == 1 -> L("suggest.keep_episode")
                        ownWords && row.note.isEmpty() -> L(if (row.existing) "add.open" else "suggest.keep")
                        row.note.isEmpty() -> L(if (row.existing) "suggest.link_entry" else "suggest.add_to_carnet")
                        row.existing -> L("suggest.add_note")
                        else -> L("suggest.keep")
                    },
                    fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
            }
            if (working) { Spacer(Modifier.width(10.dp)); CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = theme.inkMute) }
        }
        // Not the right one: Maurice looks again, or the member says what to look for.
        if (row.searchable == true) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { search(null) }, enabled = !searching && !working) {
                    Icon(androidx.compose.material.icons.Icons.Default.Refresh, null, tint = theme.inkSoft, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(L("suggest.search_again"), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = theme.inkSoft)
                }
                TextButton(onClick = { refining = !refining; if (refining) term = row.query ?: row.title }, enabled = !searching && !working) {
                    Icon(androidx.compose.material.icons.Icons.Default.Search, null, tint = theme.inkSoft, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(L("suggest.search_refine"), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = theme.inkSoft)
                }
                if (searching) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = theme.inkMute)
            }
            if (refining) Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    term, { term = it }, singleLine = true, placeholder = { Text(L("suggest.search_placeholder"), fontSize = 13.sp) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search(term) }),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { search(term) }, enabled = !searching && term.isNotBlank()) { Text(L("suggest.search_go"), fontSize = 12.sp, color = theme.ink) }
            }
            nothing?.let { Text(it, fontSize = 11.sp, color = theme.inkMute) }
        }
    }
}

/**
 * Kept: the entry, and the way to it — in Carnet where it is installed
 * (`carnet://entry/…`), else its page in the member's garden. A person opens
 * in the garden: Carnet's list does not hold people.
 */
@Composable
private fun KeptRow(row: EntrySuggestion, serverUrl: String) {
    val theme = LocalMauriceTheme.current
    val uri = LocalUriHandler.current
    val canOpen = row.webPath != null || (row.entry != null && row.kind != "people")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = canOpen, role = androidx.compose.ui.semantics.Role.Button) {
            val inCarnet = row.kind != "people" && row.entry != null && runCatching { uri.openUri("carnet://entry/${row.entry}") }.isSuccess
            if (!inCarnet) row.webPath?.let { runCatching { uri.openUri(it) } }
        }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(row.image, row.kind, serverUrl, 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = theme.ink)
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindPill(row.kind, compact = true)
                row.year?.let { Spacer(Modifier.width(6.dp)); Text("$it", fontSize = 11.sp, color = theme.inkSoft) }
            }
        }
        Icon(androidx.compose.material.icons.Icons.Default.CheckCircle, null, tint = theme.inkMute, modifier = Modifier.size(14.dp))
        if (canOpen) { Spacer(Modifier.width(8.dp)); OpenMark() }
    }
}
