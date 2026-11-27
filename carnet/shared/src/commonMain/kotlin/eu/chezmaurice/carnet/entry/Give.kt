package eu.chezmaurice.carnet.entry

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.chezmaurice.carnet.CarnetRouter
import eu.chezmaurice.carnet.platform.longDate
import eu.chezmaurice.carnet.ui.Face
import eu.chezmaurice.carnet.ui.GridButton
import eu.chezmaurice.carnet.ui.GridIconButton
import eu.chezmaurice.carnet.ui.GridLabel
import eu.chezmaurice.carnet.ui.GridLoading
import eu.chezmaurice.carnet.ui.GridMessage
import eu.chezmaurice.carnet.ui.GridRule
import eu.chezmaurice.carnet.ui.MarkdownBlocks
import eu.chezmaurice.carnet.ui.MemberBadge
import eu.chezmaurice.carnet.ui.paper
import eu.chezmaurice.core.carnet.ConversationExport
import eu.chezmaurice.core.carnet.CrossHouseholdApi
import eu.chezmaurice.core.carnet.EntryExport
import eu.chezmaurice.core.carnet.ImportPayload
import eu.chezmaurice.core.carnet.ShelfEntry
import eu.chezmaurice.core.model.Household
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.model.parseServerDate
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.maurice.absoluteUrl
import eu.chezmaurice.maurice.consent.LocalAppLanguage
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.ui.Strings
import eu.chezmaurice.ui.wordmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

// Giving an entry to someone in another household this device is signed into
// (`GiveEntrySheet.swift`, cross-household-share.md section D). The entry is
// copied there — a gift, not a link: the member ticks every element that
// leaves, sees all of it in a preview whose button is the only « Send », and
// the copy lands in their own notes over there, shared with that person. The
// member may also copy it to their own notes over there, shared with no one
// (« Me — in my notes »): the same sheet and preview, without `share_with`.

/**
 * Someone in another household — or the member themselves there ([isSelf]) —
 * and the member of this device whose session reaches that household ([via]).
 */
data class GiveRecipient(val household: Household, val via: String, val member: Member, val isSelf: Boolean = false)

/** What the member ticked. Everything is unticked but the source. */
data class GiveSelection(
    val source: Boolean = true,
    val prose: Boolean = false,
    val notes: Set<String> = emptySet(),
    val highlights: Set<String> = emptySet(),
    val card: Boolean = false,
    val conversations: Map<String, Mode> = emptyMap(),
) {
    enum class Mode { Summary, Whole }
}

// ── The sections in the share sheet ─────────────────────

private sealed interface RosterState {
    data object Loading : RosterState
    data object Failed : RosterState
    data object NoSession : RosterState
    /** The people the member can give to there, and the member's own account there. */
    data class People(val people: List<Member>, val me: Member, val via: String) : RosterState
}

/**
 * One section per other household of the device: the people the member can
 * reach there, read with that household's own session. Choosing someone
 * opens the sending sheet. [onOpened]: the copy was opened over there.
 */
@Composable
fun OtherHouseholdsSection(session: Session, entry: ShelfEntry, onOpened: () -> Unit) {
    val state by session.state.collectAsState()
    val others = state.households.filter { it.id != state.currentId }
    if (others.isEmpty()) return
    val theme = LocalMauriceTheme.current
    val rosters = remember { mutableStateMapOf<String, RosterState>() }
    var giving by remember { mutableStateOf<GiveRecipient?>(null) }

    /** A session of the member's in that household: the active one there, or any other member of this device signed in there. */
    fun via(h: Household): String? = (listOfNotNull(h.activeMemberId) + h.members.map { it.id }).firstOrNull { session.hasToken(h.id, it) }

    LaunchedEffect(Unit) {
        // The households' own names, for the sections' headings.
        session.refreshHouseholds()
        val reachable = session.state.value.households.filter { it.id != session.state.value.currentId }.mapNotNull { h ->
            val id = via(h)
            if (id == null) { rosters[h.id] = RosterState.NoSession; null } else Triple(h, id, session.apiAs(h.id, id))
        }
        val found = coroutineScope {
            reachable.map { (h, id, api) ->
                async { Triple(h, id, api?.let { runCatching { CrossHouseholdApi(it).roster() }.getOrNull() }) }
            }.awaitAll()
        }
        for ((h, id, people) in found) {
            if (people == null) { rosters[h.id] = RosterState.Failed; continue }
            // The member's own account there: as the household lists it, else as this device keeps it.
            val mine = people.firstOrNull { it.id == id } ?: h.members.firstOrNull { it.id == id }
            rosters[h.id] = if (mine == null) RosterState.Failed else RosterState.People(people.filter { it.id != id }, mine, id)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        for (h in others) Column {
            GridLabel(L("give.section", h.name), 9.5.sp, modifier = Modifier.padding(bottom = 4.dp).semantics { heading() })
            Text(L("give.section.hint"), fontSize = 12.5.sp, color = theme.inkMute, modifier = Modifier.padding(bottom = 6.dp))
            @Composable fun line(text: String) = Text(text, fontSize = 13.sp, color = theme.inkMute, modifier = Modifier.padding(vertical = 10.dp))
            @Composable fun person(m: Member, name: String, button: String, onClick: () -> Unit) {
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // A picture's path is on that household's server, not the one on screen.
                    MemberBadge(m.displayName, m.avatarColor, absoluteUrl(h.serverUrl, m.avatarUrl), 30.dp)
                    Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = theme.ink, modifier = Modifier.weight(1f))
                    GridButton(button, small = true, onClick = onClick)
                }
                GridRule()
            }
            when (val r = rosters[h.id] ?: RosterState.Loading) {
                RosterState.Loading -> line(L("give.section.loading"))
                RosterState.NoSession -> line(L("give.section.signin", h.name))
                RosterState.Failed -> line(L("give.error.offline", h.name))
                is RosterState.People -> {
                    // First, the member themselves: their own notes over there.
                    person(r.me, L("give.self.row"), L("give.self.copy")) { giving = GiveRecipient(h, r.via, r.me, isSelf = true) }
                    if (r.people.isEmpty()) line(L("give.section.empty"))
                    for (m in r.people) person(m, m.displayName, L("give.choose")) { giving = GiveRecipient(h, r.via, m) }
                }
            }
        }
    }
    giving?.let { recipient ->
        GiveEntrySheet(session, entry, recipient, fromHousehold = state.current?.name ?: "Household",
            onDismiss = { giving = null }, onOpened = { giving = null; onOpened() })
    }
}

// ── The sending sheet ───────────────────────────────────

/** The form (what leaves, ticked element by element), then the preview, whose button is the only « Send ». */
@Composable
fun GiveEntrySheet(session: Session, entry: ShelfEntry, recipient: GiveRecipient, fromHousehold: String, onDismiss: () -> Unit, onOpened: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    var export by remember { mutableStateOf<EntryExport?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf(GiveSelection()) }
    var previewing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val c = entry.collection; val l = entry.locale; val s = entry.slug
        val api = session.api()
        if (c == null || l == null || s == null || api == null) { failure = strings("give.load_failed", "—"); return@LaunchedEffect }
        try { export = CrossHouseholdApi(api).export(c, l, s) } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            failure = strings("give.load_failed", GiveMessages.describe(e, recipient, strings))
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(theme.bg).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (previewing) GridIconButton(Icons.AutoMirrored.Filled.ArrowBack, L("carnet.back")) { previewing = false }
                else GridIconButton(Icons.Filled.Close, L("common.cancel")) { onDismiss() }
                Text(
                    when {
                        previewing -> L("give.preview.title")
                        recipient.isSelf -> L("give.self.title", recipient.household.name)
                        else -> L("give.title", recipient.member.displayName, recipient.household.name)
                    },
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Spacer(Modifier.width(40.dp))
            }
            val e = export
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    e != null && previewing -> GivePreview(session, e, selection, recipient, fromHousehold, onOpened)
                    e != null -> GiveForm(e, selection, { selection = it }) { previewing = true }
                    failure != null -> GridMessage(failure!!)
                    else -> GridLoading()
                }
            }
        }
    }
}

@Composable
private fun GiveForm(e: EntryExport, selection: GiveSelection, onChange: (GiveSelection) -> Unit, onPreview: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val language = LocalAppLanguage.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        if (e.source != null) TickRow(if (selection.source) Mark.On else Mark.Off, GiveMessages.sourceLabel(e.sourceKind, strings), detail = "« ${e.title} »") {
            onChange(selection.copy(source = !selection.source))
        }

        // The fiche: its text and each note.
        val prose = e.fiche?.prose?.trim()?.takeIf { it.isNotEmpty() }
        val notes = e.fiche?.notes.orEmpty()
        if (prose != null || notes.isNotEmpty()) {
            val count = (if (prose != null) 1 else 0) + notes.size
            val ticked = (if (prose != null && selection.prose) 1 else 0) + notes.count { it.id in selection.notes }
            TickRow(Mark.of(ticked, count), L("give.fiche")) {
                val on = ticked < count
                onChange(selection.copy(prose = on && prose != null, notes = if (on) notes.map { it.id }.toSet() else emptySet()))
            }
            if (prose != null) TickRow(if (selection.prose) Mark.On else Mark.Off, L("give.fiche.prose"), level = 1) { onChange(selection.copy(prose = !selection.prose)) }
            for (n in notes) TickRow(if (n.id in selection.notes) Mark.On else Mark.Off, GiveMessages.noteLine(n, strings, language), level = 1, lines = 2) {
                onChange(selection.copy(notes = selection.notes.toggled(n.id)))
            }
        }

        val highlights = e.highlights.orEmpty()
        if (highlights.isNotEmpty()) {
            val ticked = highlights.count { it.id in selection.highlights }
            TickRow(Mark.of(ticked, highlights.size), L("give.highlights", highlights.size)) {
                onChange(selection.copy(highlights = if (ticked < highlights.size) highlights.map { it.id }.toSet() else emptySet()))
            }
            // Each passage can be let go once the group is ticked.
            if (ticked > 0) for (h in highlights) TickRow(if (h.id in selection.highlights) Mark.On else Mark.Off, "« ${h.text} »", level = 1, lines = 2) {
                onChange(selection.copy(highlights = selection.highlights.toggled(h.id)))
            }
        }

        e.card?.let { card ->
            TickRow(if (selection.card) Mark.On else Mark.Off, if (card.isDraft) L("give.card.draft") else L("give.card.published"), detail = if (card.isDraft) null else L("give.card.hint")) {
                onChange(selection.copy(card = !selection.card))
            }
        }

        val conversations = e.conversations.orEmpty()
        if (conversations.isNotEmpty()) {
            val open = conversations.filter { it.shareable }
            val ticked = open.count { it.id in selection.conversations }
            fun default(c: EntryExport.Conversation) = if (c.summary == null) GiveSelection.Mode.Whole else GiveSelection.Mode.Summary
            TickRow(if (open.isEmpty()) Mark.Disabled else Mark.of(ticked, open.size), L("give.conversations")) {
                onChange(selection.copy(conversations = if (ticked < open.size) open.associate { it.id to (selection.conversations[it.id] ?: default(it)) } else emptyMap()))
            }
            for (c in conversations) {
                val title = GiveMessages.conversationLine(c, language)
                if (c.shareable) {
                    val mode = selection.conversations[c.id]
                    TickRow(if (mode != null) Mark.On else Mark.Off, title, level = 1) {
                        onChange(selection.copy(conversations = if (mode == null) selection.conversations + (c.id to default(c)) else selection.conversations - c.id))
                    }
                    if (mode != null) Row(Modifier.padding(start = (2 * INDENT + 4).dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Radio(L("give.conv.summary"), mode == GiveSelection.Mode.Summary, enabled = c.summary != null) {
                            onChange(selection.copy(conversations = selection.conversations + (c.id to GiveSelection.Mode.Summary)))
                        }
                        Radio(L("give.conv.whole"), mode == GiveSelection.Mode.Whole, enabled = true) {
                            onChange(selection.copy(conversations = selection.conversations + (c.id to GiveSelection.Mode.Whole)))
                        }
                    }
                } else TickRow(Mark.Disabled, title, detail = if (c.reason == null || c.reason == "others_took_part") L("give.conv.others") else c.reason, level = 1)
            }
            Text(L("give.conv.review"), fontSize = 12.5.sp, color = theme.inkSoft, modifier = Modifier.padding(top = 10.dp))
        }

        Spacer(Modifier.size(24.dp))
        GridButton(L("give.preview_button"), primary = true, full = true, onClick = onPreview)
    }
}

private fun Set<String>.toggled(id: String) = if (id in this) this - id else this + id

private const val INDENT = 26

private enum class Mark {
    On, Off, Mixed, Disabled;
    companion object { fun of(ticked: Int, count: Int) = if (ticked == 0) Off else if (ticked == count) On else Mixed }
}

/** One line of the sheet: a box, its title, an optional second line. */
@Composable
private fun TickRow(state: Mark, title: String, detail: String? = null, level: Int = 0, lines: Int = 1, onClick: (() -> Unit)? = null) {
    val theme = LocalMauriceTheme.current
    val enabled = state != Mark.Disabled && onClick != null
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onClick?.invoke() }.padding(start = (level * INDENT).dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            when (state) { Mark.On -> "☑"; Mark.Off -> "☐"; Mark.Mixed -> "⊟"; Mark.Disabled -> "⊘" },
            fontSize = 17.sp, color = if (state == Mark.Disabled) theme.inkMute else theme.ink,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = if (level == 0) 15.sp else 14.sp, fontWeight = if (level == 0) FontWeight.SemiBold else FontWeight.Normal,
                color = if (state == Mark.Disabled) theme.inkMute else theme.ink, maxLines = lines, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, fontSize = 12.5.sp, color = theme.inkMute, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun Radio(title: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Row(Modifier.clickable(enabled = enabled, onClick = onClick), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (on) "◉" else "○", fontSize = 15.sp, color = if (enabled) theme.ink else theme.inkMute)
        Text(title, fontSize = 13.sp, color = if (enabled) theme.ink else theme.inkMute)
    }
}

// ── The preview ─────────────────────────────────────────

private sealed interface Outcome {
    data class Sent(val imported: CrossHouseholdApi.Imported) : Outcome
    data class Exists(val ref: String) : Outcome
    data class Failed(val message: String) : Outcome
}

/**
 * The entry as it will arrive over there: the source, the member's face
 * (prose, notes, passages as quotes, conversations kept as their summary),
 * the card in draft, each conversation sent whole in full.
 */
@Composable
private fun GivePreview(session: Session, export: EntryExport, selection: GiveSelection, recipient: GiveRecipient, fromHousehold: String, onOpened: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val language = LocalAppLanguage.current
    val scope = rememberCoroutineScope()
    val wholes = remember { mutableStateMapOf<String, ConversationExport>() }
    var conversationFailure by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var fullText by remember { mutableStateOf(false) }

    val name = recipient.member.displayName
    val place = recipient.household.name
    val notes = export.fiche?.notes.orEmpty().filter { it.id in selection.notes }
    val highlights = export.highlights.orEmpty().filter { it.id in selection.highlights }
    val prose = if (selection.prose) export.fiche?.prose else null
    val conversations = export.conversations.orEmpty().mapNotNull { c -> selection.conversations[c.id]?.takeIf { c.shareable }?.let { c to it } }
    val summaries = conversations.filter { it.second == GiveSelection.Mode.Summary }.map { it.first }
    val fulls = conversations.filter { it.second == GiveSelection.Mode.Whole }.map { it.first }
    val ready = fulls.all { it.id in wholes }
    val hasMine = prose != null || notes.isNotEmpty() || highlights.isNotEmpty() || summaries.isNotEmpty()

    LaunchedEffect(Unit) {
        val api = session.api() ?: return@LaunchedEffect
        for (c in fulls) if (c.id !in wholes) try { wholes[c.id] = CrossHouseholdApi(api).conversation(c.id) } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            conversationFailure = strings("give.preview.conv_failed", GiveMessages.describe(e, recipient, strings))
        }
    }

    fun open(ref: String) {
        onOpened()
        // Over to the other household, on the copy.
        session.selectHousehold(recipient.household.id)
        CarnetRouter.open("carnet://entry/$ref")
    }

    fun send() {
        if (!ready) return
        sending = true
        scope.launch {
            val payload = ImportPayload(
                entry = export.entry, fromHousehold = fromHousehold,
                source = if (selection.source) export.source else null,
                prose = prose, notes = notes.map { it.raw }, highlights = highlights.map { it.raw },
                card = if (selection.card) export.card else null,
                conversations = conversations.map { (c, mode) -> c to (if (mode == GiveSelection.Mode.Whole) wholes[c.id] else null) },
                // Into the member's own notes there: shared with no one.
                shareWith = if (recipient.isSelf) null else (recipient.member.username ?: recipient.member.id),
            )
            outcome = try {
                val api = session.apiAs(recipient.household.id, recipient.via) ?: throw CrossHouseholdApi.Failure.SessionEnded
                Outcome.Sent(CrossHouseholdApi(api).import(payload))
            } catch (e: CancellationException) { throw e } catch (e: CrossHouseholdApi.Failure.Exists) {
                Outcome.Exists("${e.collection}/${e.locale}/${e.slug}")
            } catch (e: Throwable) {
                Outcome.Failed(GiveMessages.describe(e, recipient, strings))
            }
            sending = false
        }
    }

    val box = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GridLabel(if (recipient.isSelf) L("give.self.kicker", place) else L("give.preview.kicker", place), 9.5.sp, color = theme.inkSoft)
                Text(export.title, style = wordmark(24.sp), color = theme.ink)
                export.author?.let { Text(it, fontSize = 14.sp, color = theme.inkSoft) }
                Text(if (recipient.isSelf) L("give.self.given", fromHousehold) else L("give.preview.given", fromHousehold), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = theme.inkMute)
            }
            val source = export.source
            if (selection.source && source != null) Column(Modifier.fillMaxWidth().clip(box).background(theme.surfaceAlt).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GridLabel(GiveMessages.sourceLabel(export.sourceKind, strings), 9.5.sp)
                if (export.sourceKind == "book") {
                    val calibre = source["calibre"] as? kotlinx.serialization.json.JsonObject
                    Text(calibre?.text("title") ?: export.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = theme.ink)
                    val authors = (calibre?.get("authors") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
                    if (authors.isNotEmpty()) Text(authors.joinToString(", "), fontSize = 13.sp, color = theme.inkSoft)
                    Text(L("give.preview.book_record"), fontSize = 12.5.sp, color = theme.inkMute)
                } else {
                    val text = source.text("text") ?: GiveMessages.plainText(source.text("html").orEmpty())
                    if (text.isEmpty()) Text(export.title, fontSize = 14.sp, color = theme.inkSoft)
                    else {
                        Text(text, fontFamily = FontFamily.Serif, fontSize = 15.sp, color = theme.ink, maxLines = if (fullText) Int.MAX_VALUE else 8, overflow = TextOverflow.Ellipsis)
                        Text(if (fullText) L("give.show_less") else L("give.show_all"), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = theme.ink,
                            modifier = Modifier.clickable { fullText = !fullText })
                    }
                }
            }
            if (hasMine) Column(
                Modifier.fillMaxWidth().paper(Face.Mine, box, theme).border(0.5.dp, theme.rule, box).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                GridLabel(L("give.preview.mine"), 9.5.sp, color = theme.inkSoft)
                prose?.takeIf { it.isNotEmpty() }?.let { MarkdownBlocks(it, serif = false) }
                for (n in notes) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GridLabel(GiveMessages.noteHeading(n, language), 9.5.sp)
                    n.quote?.takeIf { it.isNotEmpty() }?.let { MarkdownBlocks("> $it", serif = false) }
                    MarkdownBlocks(n.text, serif = false)
                }
                for (h in highlights) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MarkdownBlocks("> " + h.text, serif = false)
                    h.chapter?.takeIf { it.isNotEmpty() }?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = theme.inkMute, modifier = Modifier.padding(start = 11.dp)) }
                    h.note?.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 13.5.sp, color = theme.ink, modifier = Modifier.padding(start = 11.dp)) }
                }
                for (c in summaries) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val date = GiveMessages.dateLabel(c.date, language).orEmpty()
                    GridLabel(date, 9.5.sp)
                    Text(L("give.preview.conv_note", date, c.summary.orEmpty()), fontSize = 14.sp, color = theme.ink)
                }
            }
            if (selection.card) export.card?.let { card ->
                Column(Modifier.fillMaxWidth().paper(Face.Shared, box, theme).border(0.5.dp, theme.rule, box).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GridLabel(L("give.preview.card"), 9.5.sp, color = theme.inkSoft)
                    MarkdownBlocks(withoutTitle(card.body, export.title), serif = true)
                }
            }
            for (c in fulls) Column(Modifier.fillMaxWidth().clip(box).border(0.5.dp, theme.ruleHard, box)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GridLabel(L("give.preview.conv_label", fromHousehold), 9.5.sp, color = theme.inkSoft)
                    Text(wholes[c.id]?.title ?: c.title ?: "Maurice", style = wordmark(18.sp), color = theme.ink)
                    GiveMessages.dateLabel(c.date, language)?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = theme.inkMute) }
                }
                GridRule()
                val whole = wholes[c.id]
                when {
                    whole != null -> for (m in whole.messages) {
                        Column(Modifier.fillMaxWidth().background(if (m.isMaurice) Color.Transparent else theme.surfaceAlt).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GridLabel(if (m.isMaurice) "Maurice" else (m.author ?: L("give.preview.you")), 9.5.sp, color = if (m.isMaurice) theme.inkSoft else theme.inkMute)
                            MarkdownBlocks(m.text, serif = false)
                        }
                        GridRule()
                    }
                    conversationFailure != null -> Text(conversationFailure!!, fontSize = 13.sp, color = theme.inkSoft, modifier = Modifier.padding(16.dp))
                    else -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = theme.inkMute) }
                }
            }
            if (!selection.source && !hasMine && !selection.card && fulls.isEmpty()) Text(L("give.preview.nothing"), fontSize = 13.sp, color = theme.inkMute)
        }
        // The footer: the only « Send », then what came of it.
        Column(Modifier.fillMaxWidth().background(theme.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            @Composable fun sendButton() {
                if (sending) Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = theme.inkMute)
                    Spacer(Modifier.width(8.dp))
                    Text(if (recipient.isSelf) L("give.self.sending") else L("give.sending"), fontSize = 14.sp, color = theme.inkSoft)
                } else GridButton(if (recipient.isSelf) L("give.self.copy") else L("give.send", name), primary = true, full = true, enabled = ready) { send() }
            }
            when (val o = outcome) {
                is Outcome.Sent -> {
                    // Copied to one's own notes, there is no one to talk it over with.
                    Text(if (recipient.isSelf) L("give.self.sent", place) else L("give.sent", name, place), fontSize = 14.sp, color = theme.ink)
                    GridButton(L("give.open"), primary = true, full = true) { open(o.imported.ref) }
                }
                is Outcome.Exists -> {
                    Text(if (recipient.isSelf) L("give.self.exists", place) else L("give.error.exists", name, place), fontSize = 14.sp, color = theme.ink)
                    GridButton(L("give.open"), primary = true, full = true) { open(o.ref) }
                }
                is Outcome.Failed -> { Text(o.message, fontSize = 13.5.sp, color = theme.inkSoft); sendButton() }
                null -> sendButton()
            }
        }
    }
}

private fun kotlinx.serialization.json.JsonObject.text(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

// ── Words ───────────────────────────────────────────────

/** What the sending sheet says (`GiveMessages`), in the app's language. */
object GiveMessages {
    fun sourceLabel(kind: String?, strings: Strings): String = when (kind) {
        "article" -> strings("give.source.article")
        "book" -> strings("give.source.book")
        else -> strings("give.source.other")
    }

    /** « 12 mars » from "2026-03-12" (or any date the server writes); the year when it is not this one. The raw text when it does not read. */
    @OptIn(ExperimentalTime::class)
    fun dateLabel(raw: String?, language: String, thisYear: Int = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).year): String? {
        if (raw.isNullOrEmpty()) return null
        val date = runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()
            ?: parseServerDate(raw)?.toLocalDateTime(TimeZone.UTC)?.date
            ?: return raw
        return longDate(date.year, date.month.ordinal + 1, date.day, withYear = date.year != thisYear, language = language)
    }

    /** « 12 mars — « Relire le passage… » » / « 14 mars — p. 32 : « … » » */
    fun noteLine(note: EntryExport.Note, strings: Strings, language: String): String {
        val text = note.text.replace("\n", " ")
        val body = note.pageLabel?.let { strings("give.note.page", it, text) } ?: "« $text »"
        return listOfNotNull(dateLabel(note.date, language), body).joinToString(" — ")
    }

    fun noteHeading(note: EntryExport.Note, language: String): String =
        listOfNotNull(dateLabel(note.date, language), note.pageLabel?.let { "p. $it" }).joinToString(" · ")

    fun conversationLine(c: EntryExport.Conversation, language: String): String =
        listOfNotNull(dateLabel(c.date, language), c.title).joinToString(" · ")

    /** An error said in the app's language, naming the person and the household. */
    fun describe(error: Throwable, recipient: GiveRecipient, strings: Strings): String {
        val place = recipient.household.name
        val name = recipient.member.displayName
        return when (error) {
            CrossHouseholdApi.Failure.Offline -> strings("give.error.offline", place)
            is CrossHouseholdApi.Failure.Forbidden -> if (error.code == "others_took_part") strings("give.conv.others") else strings("give.error.unreachable", name, place)
            CrossHouseholdApi.Failure.Unreachable -> strings("give.error.unreachable", name, place)
            CrossHouseholdApi.Failure.SessionEnded -> strings("give.error.session", place)
            CrossHouseholdApi.Failure.Unsupported -> strings("give.error.unsupported", place)
            CrossHouseholdApi.Failure.TooLarge -> strings("give.error.too_large", place)
            is CrossHouseholdApi.Failure.Exists -> if (recipient.isSelf) strings("give.self.exists", place) else strings("give.error.exists", name, place)
            // The server says the person cannot be shared with any more.
            is CrossHouseholdApi.Failure.Refused ->
                if (error.code in UNREACHABLE || "reach" in error.code) strings("give.error.unreachable", name, place) else error.code
            is CrossHouseholdApi.Failure.Other -> error.message.orEmpty()
            else -> error.message.orEmpty()
        }
    }

    private val UNREACHABLE = setOf("not_reachable", "unreachable", "unknown_member", "not_a_contact", "forbidden", "share_with")

    /** The captured page as plain paragraphs, for the preview. */
    fun plainText(html: String): String {
        if (html.isEmpty()) return ""
        var s = html
        for ((pattern, replacement) in listOf(
            "(?is)<(script|style)[^>]*>.*?</\\1>" to "",
            "(?i)<br\\s*/?>" to "\n",
            "(?i)</(p|div|h[1-6]|li|blockquote)>" to "\n\n",
            "<[^>]+>" to "",
        )) s = s.replace(Regex(pattern), replacement)
        for ((entity, char) in listOf("&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'",
            "&rsquo;" to "’", "&laquo;" to "«", "&raquo;" to "»")) s = s.replace(entity, char)
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex("\\n\\s*\\n\\s*(\\n\\s*)+"), "\n\n")
        return s.trim()
    }
}
