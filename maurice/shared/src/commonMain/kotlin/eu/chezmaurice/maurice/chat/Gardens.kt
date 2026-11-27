package eu.chezmaurice.maurice.chat

import eu.chezmaurice.ui.accessibilityName
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.gardens.Garden
import eu.chezmaurice.core.gardens.GardenMember
import eu.chezmaurice.core.gardens.GardenNote
import eu.chezmaurice.core.gardens.GardenSort
import eu.chezmaurice.core.gardens.GardensStore
import eu.chezmaurice.core.gardens.NoteAccess
import eu.chezmaurice.core.gardens.NoteAccessMember
import eu.chezmaurice.core.model.Role
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.maurice.absoluteUrl
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.maurice.domains.ageOf
import eu.chezmaurice.ui.Avatar
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.ui.Strings
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Shared gardens — `ScopePickerView.swift` (GardenRow, GardenPageView,
// NoteAccessSheet). The sidebar's NOTES section lists the member's own garden
// first (the leaf), then the sets of notes shared with the same people, each
// named by them. Every garden and note carries the same two ways in: a boxed ↗
// that browses it on the web, signed in, and › that manages it in the app.

/**
 * The gardens of the member signed in, and how they open in the browser.
 * [platform] is the system's own handler: the links are already signed in.
 */
class GardensHub(
    val store: GardensStore,
    private val session: Session,
    private val platform: UriHandler,
    private val scope: CoroutineScope,
) {
    val me: String? get() = session.state.value.activeMember?.id
    private val household get() = session.state.value.current

    /** Members of the household who are not guests — "The whole house" is all of them. */
    val householdSize: Int get() = household?.members.orEmpty().count { it.role != Role.Guest }

    val serverUrl: String get() = household?.serverUrl.orEmpty()

    /** The member's own garden follows their web-theme preference (Settings agrees). */
    fun themeId(g: Garden): String = if (g.mine) session.prefs.value.webTheme else g.webTheme

    fun setTheme(id: String, g: Garden) {
        scope.launch {
            if (g.mine) session.setPrefs(session.prefs.value.copy(webTheme = id))
            store.setTheme(id, g)
        }
    }

    /** People-named: "My notes", "You & Nadia", "The whole house" — `GardensStore.label(for:)`. */
    fun label(g: Garden, strings: Strings): String = GardensStore.label(g, me, householdSize) { strings(it) }

    fun browseGarden(g: Garden) = open(if (g.mine) null else g.webPath, themeId(g))
    fun browseNote(n: GardenNote) = open(n.webPath, null)

    private fun open(path: String?, theme: String?) {
        scope.launch { session.signedInUrl(path, theme)?.let(platform::openUri) }
    }

    /** host/path the garden lives at, for the page's quiet meta line. */
    fun hostLine(g: Garden): String = runCatching { Url(serverUrl).host }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { it + g.webPath } ?: g.webPath

    /** Remembered across launches: a gardener who sorts by title means it. */
    var sort: GardenSort
        get() = GardenSort.of(session.store.get(SORT_KEY))
        set(value) = session.store.put(SORT_KEY, value.raw)

    private companion object {
        const val SORT_KEY = "maurice.garden.sort"
    }
}

val LocalGardens = staticCompositionLocalOf<GardensHub?> { null }

@Composable
private fun gardenLabel(hub: GardensHub, g: Garden): String {
    val strings = LocalStrings.current
    return hub.label(g, strings)
}

/** The member's colour, a faint disc behind the leaf: their own garden's face. */
@Composable
private fun Leaf(size: Dp) {
    val accent = LocalAccent.current
    val theme = LocalMauriceTheme.current
    Box(Modifier.size(size).clip(CircleShape).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(DomainGlyphs.of("leaf"), null, tint = legible(accent, theme.isDark), modifier = Modifier.size(size * 0.55f))
    }
}

/** A few faces, overlapping — who a shared set is kept with. */
@Composable
private fun Faces(people: List<GardenMember>, serverUrl: String, size: Dp, max: Int) {
    val theme = LocalMauriceTheme.current
    val shown = people.take(max)
    Box(Modifier.width(size + (size * 0.6f) * (shown.size - 1).coerceAtLeast(0)).height(size)) {
        shown.forEachIndexed { i, p ->
            Box(Modifier.offset(x = size * 0.6f * i).clip(CircleShape).border(1.5.dp, theme.surfaceAlt, CircleShape)) {
                Avatar(p.displayName, p.avatarColor, absoluteUrl(serverUrl, p.avatarUrl), size = size)
            }
        }
    }
}

/** The ↗ in its box: browse on the web. */
@Composable
private fun BrowseBox(box: Dp, label: String, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(box).border(0.5.dp, theme.ruleHard, RoundedCornerShape(box * 0.28f)), contentAlignment = Alignment.Center) {
            Icon(DomainGlyphs.of("arrow.up.forward"), label, tint = theme.inkSoft, modifier = Modifier.size(box * 0.5f))
        }
    }
}

/** Mono uppercase kicker: NOTES · 3, ALL THESE NOTES, WEB THEME. */
@Composable
private fun Kicker(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp, color = LocalMauriceTheme.current.inkMute, modifier = modifier)
}

/**
 * The sidebar's NOTES section: your garden, then the shared sets. Collapsible,
 * like the conversations under it; absent while there is no garden.
 */
@Composable
fun GardensSection(hub: GardensHub, onOpen: (String) -> Unit) {
    val theme = LocalMauriceTheme.current
    val state by hub.store.state.collectAsState()
    if (state.gardens.isEmpty()) return
    var expanded by remember { mutableStateOf(true) }
    Row(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(DomainGlyphs.of("chevron.right"), null, tint = theme.inkMute, modifier = Modifier.size(10.dp).rotate(if (expanded) 90f else 0f))
        Kicker("${L("gardens.section")} · ${state.gardens.size}")
    }
    if (!expanded) return
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        for (g in state.gardens) GardenRow(hub, g, active = state.openGardenId == g.id) { onOpen(g.id) }
    }
}

@Composable
private fun GardenRow(hub: GardensHub, g: Garden, active: Boolean, onOpen: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (active) accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClickLabel = L("gardens.open_tool"), onClick = onOpen).padding(start = 10.dp, end = 4.dp).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (g.mine) Leaf(26.dp) else Faces(GardensStore.others(g, hub.me), hub.serverUrl, 26.dp, max = 2)
        Text(
            gardenLabel(hub, g), fontSize = 15.sp, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            color = if (active) theme.ink else theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        BrowseBox(28.dp, L("gardens.browse_garden")) { hub.browseGarden(g) }
        Icon(DomainGlyphs.of("chevron.right"), null, tint = if (active) legible(accent, theme.isDark) else theme.inkMute, modifier = Modifier.size(14.dp))
    }
}

/**
 * A garden's page — `GardenPageView`, the › destination: who keeps it, its
 * search, its web theme, its notes. A note opens who has access to it; a fiche
 * or a card opens on the web.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GardenPage(hub: GardensHub, garden: Garden, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(hub.sort) }
    var themes by remember { mutableStateOf(listOf("default" to "Default")) }
    var access by remember { mutableStateOf<GardenNote?>(null) }
    LaunchedEffect(hub) { themes = hub.store.webThemes() }
    val notes = remember(garden.notes, query, sort) { GardensStore.filtered(garden.notes, query, sort) }
    val label = gardenLabel(hub, garden)

    Column(modifier.background(theme.bg).statusBarsPadding()) {
        // The set, and the way out to the browser: in the app one tends, in the browser one strolls.
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onBack != null) Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(DomainGlyphs.of("chevron.left"), L("chat.back_to_conversations"), tint = theme.inkSoft, modifier = Modifier.size(20.dp))
            }
            if (!garden.mine) Faces(GardensStore.others(garden, hub.me), hub.serverUrl, 22.dp, max = 3)
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            // "My notes" reads alone; a shared set gets "/ notes".
            if (!garden.mine) {
                Text("/", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = theme.inkMute)
                Text(L("gardens.garden"), fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 15.sp, color = theme.inkSoft, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(CircleShape).background(accent).clickable { hub.browseGarden(garden) }.padding(horizontal = 11.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(L("gardens.browse"), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1)
                Icon(DomainGlyphs.of("arrow.up.forward"), null, tint = Color.White, modifier = Modifier.size(11.dp))
            }
        }
        HorizontalDivider(color = theme.rule, thickness = 0.5.dp)

        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 46.dp)) {
            item {
                Column(Modifier.widthIn(max = 620.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (garden.mine) Leaf(30.dp) else Faces(garden.members, hub.serverUrl, 30.dp, max = 4)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(label, fontFamily = FontFamily.Serif, fontSize = 27.sp, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${garden.notes.size} ${L("gardens.notes")} · ${hub.hostLine(garden)}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = theme.inkMute, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        }
                    }
                    // The search runs over the whole garden.
                    Row(
                        Modifier.padding(top = 22.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(theme.surface)
                            .border(0.5.dp, theme.ruleHard, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Icon(Icons.Default.Search, null, tint = theme.inkMute, modifier = Modifier.size(14.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text(L("gardens.search"), fontSize = 13.5.sp, color = theme.inkMute)
                            BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(fontSize = 13.5.sp, color = theme.ink), cursorBrush = SolidColor(theme.ink), modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Kicker(L("gardens.webtheme"), Modifier.padding(top = 24.dp))
                    FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val active = hub.themeId(garden)
                        for ((id, name) in themes) {
                            val on = id == active
                            Text(
                                name, fontSize = 12.sp, color = if (on) Color.White else theme.inkSoft,
                                modifier = Modifier.clip(CircleShape).background(if (on) accent else Color.Transparent)
                                    .border(0.5.dp, if (on) accent else theme.ruleHard, CircleShape)
                                    .clickable { hub.setTheme(id, garden) }.padding(horizontal = 11.dp, vertical = 5.dp),
                            )
                        }
                    }
                    Text(L("gardens.webtheme_hint"), fontSize = 11.5.sp, color = theme.inkMute, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.padding(top = 26.dp, bottom = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Kicker(L("gardens.all_notes"), Modifier.weight(1f))
                        SortPicker(sort) { sort = it; hub.sort = it }
                    }
                }
            }
            if (notes.isEmpty()) item {
                Box(
                    Modifier.widthIn(max = 620.dp).fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).background(theme.surface).border(0.5.dp, theme.rule, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(L(if (query.isEmpty()) "gardens.empty" else "gardens.no_match"), fontSize = 12.5.sp, color = theme.inkMute) }
            }
            itemsIndexed(notes, key = { _, n -> n.id }) { i, n ->
                val shape = when {
                    notes.size == 1 -> RoundedCornerShape(12.dp)
                    i == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                    i == notes.lastIndex -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                    else -> RoundedCornerShape(0.dp)
                }
                Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().clip(shape).background(theme.surface)) {
                    NoteRow(hub, n) { access = n }
                    if (i != notes.lastIndex) HorizontalDivider(color = theme.rule, thickness = 0.5.dp, modifier = Modifier.padding(start = 14.dp))
                }
            }
        }
    }
    access?.let { n -> NoteAccessSheet(hub, n, garden) { access = null } }
}

@Composable
private fun SortPicker(sort: GardenSort, onPick: (GardenSort) -> Unit) {
    val theme = LocalMauriceTheme.current
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            "${L(sort.labelKey)} ▾", fontSize = 11.sp, color = theme.inkSoft,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = L("sort.menu")) { open = true }.accessibilityName(L("sort.menu")).padding(horizontal = 6.dp, vertical = 4.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (s in GardenSort.entries) DropdownMenuItem(
                text = { Text((if (s == sort) "✓ " else "") + L(s.labelKey)) },
                onClick = { open = false; onPick(s) },
            )
        }
    }
}

/** One entry: what it is (leaf, fiche, card), its title, its age, ↗ and ›. */
@Composable
private fun NoteRow(hub: GardensHub, n: GardenNote, onAccess: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = L("gardens.note_access")) { if (n.isNote) onAccess() else hub.browseNote(n) }
            .padding(start = 14.dp, end = 4.dp).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        val glyph = when { n.isNote -> "leaf"; n.kind == "fiche" -> "doc.text"; else -> "newspaper" }
        Icon(DomainGlyphs.of(glyph), null, tint = theme.inkMute, modifier = Modifier.size(13.dp))
        Text(n.title, fontSize = 13.5.sp, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        // Written by Maurice, not reviewed yet: the moon of the night's work and the word.
        if (n.isUnreviewed) Row(
            Modifier.clip(CircleShape).border(0.5.dp, theme.ruleHard, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(DomainGlyphs.of("moon.stars"), null, tint = theme.inkMute, modifier = Modifier.size(9.dp))
            Text(L("gardens.unreviewed"), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = theme.inkMute)
        }
        Text(ageOf(n.updatedAt, strings), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = theme.inkMute)
        BrowseBox(20.dp, L("gardens.browse_note")) { hub.browseNote(n) }
        Icon(DomainGlyphs.of("chevron.right"), null, tint = theme.inkMute, modifier = Modifier.size(12.dp))
    }
}

/** Who has a note — share it wider, or take yourself off it (never others). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteAccessSheet(hub: GardensHub, note: GardenNote, garden: Garden, onDismiss: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = legible(LocalAccent.current, LocalMauriceTheme.current.isDark)
    val scope = rememberCoroutineScope()
    var access by remember(note.id) { mutableStateOf<NoteAccess?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    LaunchedEffect(note.id) { access = hub.store.noteAccess(note) }
    val others = garden.members.filter { it.memberId != hub.me }
    val context = if (others.isEmpty()) L("gardens.in_your_garden") else L("gardens.in_garden_with", others.joinToString(", ") { it.displayName })
    val self = access?.members?.firstOrNull { it.isSelf }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = theme.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(L("gardens.note_access").uppercase(), fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, letterSpacing = 1.4.sp, color = theme.inkMute)
                    Text(note.title, fontFamily = FontFamily.Serif, fontSize = 21.sp, color = theme.ink)
                    Text(context, fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, color = theme.inkMute)
                }
                TextButton(onClick = onDismiss) { Text("OK", fontWeight = FontWeight.SemiBold, color = accent) }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(theme.surfaceAlt).border(0.5.dp, theme.rule, RoundedCornerShape(12.dp))) {
                val members = access?.members.orEmpty()
                if (members.isEmpty()) Box(Modifier.fillMaxWidth().height(88.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), color = theme.inkSoft, strokeWidth = 2.dp) }
                members.forEachIndexed { i, m ->
                    MemberRow(hub, m, accent) {
                        scope.launch {
                            hub.store.share(note, m.memberId)
                            access = hub.store.noteAccess(note)
                        }
                    }
                    if (i != members.lastIndex) HorizontalDivider(color = theme.rule, thickness = 0.5.dp, modifier = Modifier.padding(start = 13.dp))
                }
            }
            Text(L("gardens.share_hint"), fontSize = 11.5.sp, lineHeight = 16.sp, color = theme.inkMute)
            if (self != null && self.tends && !self.isOwner) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        L("gardens.remove_self"), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = legible(Color(0xFFB3382C), theme.isDark),
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { confirmLeave = true }.padding(vertical = 4.dp),
                    )
                    Text(L("gardens.remove_hint"), fontSize = 11.sp, color = theme.inkMute, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text(L("gardens.remove_self")) },
        confirmButton = {
            TextButton(onClick = {
                confirmLeave = false
                scope.launch { hub.store.leave(note); onDismiss() }
            }) { Text(L("gardens.remove_self_confirm"), color = Color.Red) }
        },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(L("common.cancel")) } },
        containerColor = theme.surface,
    )
}

@Composable
private fun MemberRow(hub: GardensHub, m: NoteAccessMember, accent: Color, onShare: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Avatar(m.displayName, m.avatarColor, absoluteUrl(hub.serverUrl, m.avatarUrl), size = 26.dp)
        Column(Modifier.weight(1f)) {
            Text(m.displayName, fontSize = 13.sp, color = theme.ink)
            if (m.isSelf) Text(L("gardens.you_tag"), fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = theme.inkMute)
        }
        if (m.tends) {
            Text(
                L("gardens.tends").uppercase(), fontFamily = FontFamily.Monospace, fontSize = 8.5.sp, letterSpacing = 0.8.sp, color = theme.inkSoft,
                modifier = Modifier.clip(CircleShape).border(0.5.dp, theme.ruleHard, CircleShape).padding(horizontal = 7.dp, vertical = 2.dp),
            )
        } else {
            Text(
                "+ " + L("gardens.share"), fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = accent,
                modifier = Modifier.clip(CircleShape).border(0.5.dp, accent, CircleShape).clickable(onClick = onShare).padding(horizontal = 11.dp, vertical = 4.dp),
            )
        }
    }
}
