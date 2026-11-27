package eu.chezmaurice.maurice.domains

import eu.chezmaurice.ui.accessibilityLabel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.chat.ChatController
import eu.chezmaurice.core.domains.DomainStore
import eu.chezmaurice.core.model.Domain
import eu.chezmaurice.core.model.DomainProposal
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.model.ProposalDecision
import eu.chezmaurice.core.model.ProposalDetail
import eu.chezmaurice.core.model.Role
import eu.chezmaurice.maurice.consent.LocalAppLanguage
import eu.chezmaurice.maurice.chat.BackHandlerCompat
import eu.chezmaurice.maurice.chat.legible
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.wordmark
import kotlinx.coroutines.launch

/**
 * The member's domains and reading companions, with what the night proposes
 * (`DomainsListSheet` and `ProposalPage`). A domain of their own opens its
 * page; a companion its pinned conversation; a domain shared with a guest a
 * conversation bound to it. The proposal pages open inside the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainsSheet(store: DomainStore, controller: ChatController, me: Member, onOpened: () -> Unit, onDismiss: () -> Unit) {
    val theme = LocalMauriceTheme.current
    var page by remember { mutableStateOf<DomainProposal?>(null) }
    LaunchedEffect(Unit) {
        store.loadOverview()
        // Read the list, then say it was seen: the rows keep their "new" mark
        // for this visit, the badge on the button goes.
        store.loadProposals()
        store.markProposalsSeen()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = theme.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page != null) {
                    IconButton(onClick = { page = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("common.cancel"), tint = theme.ink) }
                } else Spacer(Modifier.width(12.dp))
                Text(L(if (page != null) "proposals.page.title" else "domains.title"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = theme.ink, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(L("common.done"), color = theme.ink) }
            }
            val current = page
            if (current != null) {
                BackHandlerCompat { page = null }
                ProposalPage(current, store, onDone = { page = null }, onAdopted = controller::refreshConversations)
            } else DomainsList(store, controller, me, onPage = { page = it }, onOpened = { onDismiss(); onOpened() })
        }
    }
}

@Composable
private fun DomainsList(store: DomainStore, controller: ChatController, me: Member, onPage: (DomainProposal) -> Unit, onOpened: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val nav = LocalDomainsNav.current
    val state by store.state.collectAsState()
    val strings = strings()
    val scope = rememberCoroutineScope()
    var showAll by remember { mutableStateOf(false) }
    var showSettled by remember { mutableStateOf(false) }
    val putAway = state.settled.filter { it.canRestore }

    fun open(d: Domain) {
        if (d.isDomainOf(me.id)) { nav.openBrief(d); return }
        // A companion resumes its pinned conversation; anything else without
        // a page (a shared domain) starts one bound to it.
        val pinned = state.overview[d.id]?.conversationId
        if (d.isCompanion && pinned != null) controller.open(pinned) else controller.startNew(d.id)
        onOpened()
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.domains.isEmpty() && state.companions.isEmpty() && state.proposals.isEmpty()) {
            Text(L("domains.empty"), fontSize = 13.sp, color = theme.inkSoft, modifier = Modifier.padding(bottom = 6.dp))
        }

        // What the night proposed and the member has not settled: here, and nowhere else.
        if (state.proposals.isNotEmpty()) {
            SectionHead(L("proposals.section", state.proposals.size))
            Text(L("proposals.rule"), fontSize = 12.sp, color = theme.inkMute, modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 2.dp))
            val rows = if (showAll) state.proposals else state.proposals.take(5)
            for (p in rows) ProposalRow(p, strings = strings, onOpen = { onPage(p) }, onQuick = { action ->
                scope.launch { store.apply(listOf(ProposalDecision(p.id, action))); controller.refreshConversations() }
            })
            if (state.proposals.size > 5) {
                Fold(if (showAll) L("proposals.show_less") else L("proposals.show_all", state.proposals.size), showAll) { showAll = !showAll }
            }
            Gap(6.dp)
        }

        if (state.domains.isNotEmpty()) {
            SectionHead(L("domains.section.domains"))
            for (d in state.domains) DomainRow(d, subtitle(d, me, store, strings, LocalAppLanguage.current)) { open(d) }
        }
        if (state.companions.isNotEmpty()) {
            SectionHead(L("domains.section.companions"), Modifier.padding(top = 8.dp))
            for (c in state.companions) DomainRow(c, L("domains.companion.hint")) { open(c) }
        }

        if (me.role != Role.Guest) {
            Row(
                Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .dashedBorder(theme.ruleHard, 12.dp)
                    .clickable { nav.openEditor(null) }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Add, null, tint = theme.inkSoft, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(L("domains.new"), fontSize = 14.sp, color = theme.inkSoft)
            }
        }

        Text(L("domains.explainer"), fontSize = 12.sp, color = theme.inkMute, modifier = Modifier.padding(top = 10.dp))

        // What was put away does not come back by itself; the member can come back on it.
        if (putAway.isNotEmpty()) {
            Fold(L("proposals.put_away", putAway.size), showSettled, Modifier.padding(top = 10.dp)) { showSettled = !showSettled }
            if (showSettled) for (p in putAway) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(0.5.dp, theme.ruleHard, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 14.sp, color = theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(proposalNumbers(p, strings, LocalAppLanguage.current), fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = theme.inkMute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { scope.launch { store.restore(p.id) } }) { Text(L("proposals.restore"), fontSize = 12.sp, color = legible(LocalAccent.current, theme.isDark)) }
                }
            }
        }
    }
}

/** What the row says under a domain's name: the brief's age and hand, or none yet; for a guest, that it was shared. */
private fun subtitle(d: Domain, me: Member, store: DomainStore, strings: eu.chezmaurice.ui.Strings, language: String): String {
    if (!d.isDomainOf(me.id)) return strings("domains.shared.hint")
    val stamp = store.state.value.overview[d.id]?.brief
    val at = stamp?.updatedAt ?: return strings("domains.no_brief")
    val when_ = ageOf(at, strings).ifEmpty { abbreviatedDay(at, language) ?: at }
    return strings(if (stamp.byMember) "domains.brief.by_you" else "domains.brief.by_maurice", when_)
}

@Composable
private fun DomainRow(d: Domain, subtitle: String, onTap: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(theme.bg).border(0.5.dp, theme.ruleHard, shape).clickable(onClick = onTap).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DomainMark(d, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle.ifEmpty { d.tagline }, fontSize = 11.5.sp, color = theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = theme.inkMute, modifier = Modifier.size(18.dp))
    }
}

/** One open proposal: its weight in dots, its name, a line, its numbers. A long press adopts or puts away. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ProposalRow(p: DomainProposal, strings: eu.chezmaurice.ui.Strings, onOpen: () -> Unit, onQuick: (String) -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box {
        Row(
            Modifier.fillMaxWidth().clip(shape).background(theme.bg).border(0.5.dp, theme.ruleHard, shape)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).dashedBorder(theme.ruleHard, 10.dp), contentAlignment = Alignment.Center) {
                val n = p.weight.coerceIn(0, 5)
                Text("●".repeat(n) + "○".repeat(5 - n), fontFamily = FontFamily.Monospace, fontSize = 6.sp, color = if (p.isAlive) legible(accent, theme.isDark) else theme.inkMute, maxLines = 1,
                    modifier = Modifier.accessibilityLabel(L("proposals.weight", p.weight, 5), button = false))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (p.isNew == true) { Spacer(Modifier.width(6.dp)); Box(Modifier.size(6.dp).clip(CircleShape).background(accent).accessibilityLabel(L("proposals.new"), button = false)) }
                }
                p.oneLine?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 11.5.sp, color = theme.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text(proposalNumbers(p, strings, LocalAppLanguage.current), fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = theme.inkMute, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = theme.inkMute, modifier = Modifier.padding(top = 10.dp).size(18.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(L("proposals.adopt")) }, onClick = { menu = false; onQuick("adopt") })
            DropdownMenuItem(text = { Text(L("proposals.dismiss"), color = ERASE) }, onClick = { menu = false; onQuick("dismiss") })
        }
    }
}

@Composable
private fun Fold(label: String, open: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onToggle).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (open) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = theme.inkSoft, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = theme.inkSoft)
    }
}

// ── A proposal's page ──────────────────────────────────────

private enum class Mode { View, Merge, Cut }

/**
 * Where the member decides, once per proposal, conversations and mail
 * together: adopt it (with or without notes in the garden), correct its name
 * or paragraph, merge it with others, cut a part out, or put it away.
 */
@Composable
private fun ProposalPage(proposal: DomainProposal, store: DomainStore, onDone: () -> Unit, onAdopted: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    val strings = strings()
    val scope = rememberCoroutineScope()
    val state by store.state.collectAsState()
    var name by remember(proposal.id) { mutableStateOf(proposal.name) }
    var summary by remember(proposal.id) { mutableStateOf(proposal.summary) }
    var seed by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ProposalDetail?>(null) }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(Mode.View) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var partName by remember { mutableStateOf("") }
    LaunchedEffect(proposal.id) { detail = store.proposal(proposal.id) }

    val newName = name.trim()
    val newSummary = summary.trim()
    val renamed = newName.isNotEmpty() && newName != proposal.name
    val resummarised = newSummary.isNotEmpty() && newSummary != proposal.summary
    val others = state.proposals.filter { it.id != proposal.id }

    /** One act; back to the list when it took. */
    fun run(act: suspend () -> Boolean) {
        working = true; failed = false
        scope.launch {
            val ok = act()
            working = false
            if (ok) onDone() else failed = true
        }
    }
    fun apply(action: String) = run {
        val r = store.apply(listOf(ProposalDecision(
            proposal.id, action,
            name = if (renamed) newName else null,
            summary = if (resummarised) newSummary else null,
            seed = action == "adopt" && seed,
        )))
        // An adoption binds conversations: the list's rows moved.
        if (r?.adopted?.isNotEmpty() == true) onAdopted()
        r != null && r.errors.isEmpty()
    }
    fun toggle(id: String) { picked = if (id in picked) picked - id else picked + id }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (mode) {
            Mode.View -> {
                Text(proposalNumbers(proposal, strings, LocalAppLanguage.current), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = theme.inkMute)
                OutlinedTextField(name, { name = it }, label = { Text(L("proposals.name")) }, textStyle = wordmark(20.sp), singleLine = true, enabled = !working, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(summary, { summary = it }, label = { Text(L("proposals.summary")) }, minLines = 2, maxLines = 10, enabled = !working, modifier = Modifier.fillMaxWidth())
                proposal.splitHint?.takeIf { it.isNotBlank() }?.let {
                    Text(L("proposals.split_hint", it), fontSize = 12.sp, fontStyle = FontStyle.Italic, color = theme.inkMute)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L("proposals.seed"), fontSize = 13.sp, color = theme.inkSoft, modifier = Modifier.weight(1f))
                    Switch(seed, { seed = it }, enabled = !working, colors = SwitchDefaults.colors(checkedTrackColor = accent))
                }
                Button(
                    onClick = { apply("adopt") }, enabled = !working, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                ) { Text(L("proposals.adopt"), fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (renamed || resummarised) PillButton(null, L("common.save"), enabled = !working) { apply("keep") }
                    if (others.isNotEmpty()) PillButton("arrow.triangle.merge", L("proposals.merge"), enabled = !working) { picked = emptySet(); mode = Mode.Merge }
                    val held = (detail?.conversationsList?.size ?: 0) + (detail?.mailList?.size ?: 0)
                    if (held > 1) PillButton("scissors", L("proposals.cut"), enabled = !working) { picked = emptySet(); partName = ""; mode = Mode.Cut }
                    PillButton(null, L("proposals.dismiss"), tint = ERASE, enabled = !working) { apply("dismiss") }
                }
                Text(L("proposals.dismiss.hint"), fontSize = 11.sp, color = theme.inkMute)
                Holdings(proposal, detail, strings, selectable = false, picked = picked, onToggle = ::toggle)
            }
            Mode.Merge -> {
                Text(L("proposals.merge.explainer", proposal.name), fontSize = 13.sp, color = theme.inkSoft)
                for (o in others) PickRow(o.name, proposalNumbers(o, strings, LocalAppLanguage.current), o.id in picked) { toggle(o.id) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillButton(null, L("common.cancel")) { mode = Mode.View }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { run { store.merge(listOf(proposal.id) + picked, if (renamed) newName else proposal.name) } },
                        enabled = picked.isNotEmpty() && !working,
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                    ) { Text(L("proposals.merge")) }
                }
            }
            Mode.Cut -> {
                Text(L("proposals.cut.explainer"), fontSize = 13.sp, color = theme.inkSoft)
                OutlinedTextField(partName, { partName = it }, label = { Text(L("proposals.cut.name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Holdings(proposal, detail, strings, selectable = true, picked = picked, onToggle = ::toggle)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillButton(null, L("common.cancel")) { mode = Mode.View }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            val convs = detail?.conversationsList.orEmpty().map { it.id }.filter { it in picked }
                            val mail = detail?.mailList.orEmpty().map { it.path }.filter { it in picked }
                            run { store.split(proposal.id, partName.trim(), convs, mail) }
                        },
                        enabled = picked.isNotEmpty() && partName.isNotBlank() && !working,
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                    ) { Text(L("proposals.cut.confirm", picked.size)) }
                }
            }
        }
        if (failed) Text(L("proposals.failed"), fontSize = 12.sp, color = ERASE)
    }
}

/** What a proposal holds: its conversations and mail threads, a dozen of each unless being cut. */
@Composable
private fun Holdings(proposal: DomainProposal, detail: ProposalDetail?, strings: eu.chezmaurice.ui.Strings, selectable: Boolean, picked: Set<String>, onToggle: (String) -> Unit) {
    val theme = LocalMauriceTheme.current
    if (detail == null) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = theme.inkMute) }
        return
    }
    val convs = detail.conversationsList
    val mail = detail.mailList
    if (convs.isNotEmpty()) {
        SectionHead(strings(if (convs.size == 1) "proposals.conversations.one" else "proposals.conversations.other", proposal.conversations), Modifier.padding(top = 8.dp))
        for (c in if (selectable) convs else convs.take(12)) Line(c.id, c.title.orEmpty(), c.date, selectable, c.id in picked) { onToggle(c.id) }
    }
    if (mail.isNotEmpty()) {
        SectionHead(strings(if (mail.size == 1) "proposals.mail.one" else "proposals.mail.other", proposal.mailThreads ?: mail.size), Modifier.padding(top = 8.dp))
        for (t in if (selectable) mail else mail.take(12)) Line(t.path, t.title.orEmpty(), t.from, selectable, t.path in picked) { onToggle(t.path) }
    }
}

@Composable
private fun Line(id: String, title: String, date: String?, selectable: Boolean, selected: Boolean, onToggle: () -> Unit) {
    val theme = LocalMauriceTheme.current
    if (selectable) return PickRow(title.ifEmpty { "—" }, date.orEmpty(), selected, onToggle)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(date.orEmpty().take(7), fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = theme.inkMute)
        Spacer(Modifier.width(8.dp))
        Text(title.ifEmpty { "—" }, fontSize = 13.sp, color = theme.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PickRow(title: String, sub: String, selected: Boolean, onToggle: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val accent = LocalAccent.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).background(if (selected) accent else Color.Transparent)
                .border(1.5.dp, if (selected) accent else theme.inkMute, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Text("✓", fontSize = 12.sp, color = Color.White) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.5.sp, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = theme.inkMute, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
