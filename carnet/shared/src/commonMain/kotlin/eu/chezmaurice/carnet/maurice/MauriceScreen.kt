package eu.chezmaurice.carnet.maurice

import eu.chezmaurice.ui.L
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.carnet.LocalChat
import eu.chezmaurice.carnet.Navigator
import eu.chezmaurice.carnet.Route
import eu.chezmaurice.carnet.ui.GridIconButton
import eu.chezmaurice.carnet.ui.GridLabel
import eu.chezmaurice.carnet.ui.GridLoading
import eu.chezmaurice.carnet.ui.GridRule
import eu.chezmaurice.carnet.ui.GridTitle
import eu.chezmaurice.carnet.ui.LocalApi
import eu.chezmaurice.carnet.ui.PAGE
import eu.chezmaurice.core.carnet.CalibreApi
import eu.chezmaurice.core.model.Conversation
import eu.chezmaurice.core.model.Domain
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.maurice.chat.ChatScreen
import eu.chezmaurice.maurice.domains.BriefSheet
import eu.chezmaurice.maurice.domains.DomainEditor
import eu.chezmaurice.maurice.domains.DomainsNav
import eu.chezmaurice.maurice.domains.LocalDomainsNav
import androidx.compose.runtime.CompositionLocalProvider
import eu.chezmaurice.maurice.domains.LocalDomains
import eu.chezmaurice.maurice.domains.ageOf
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.LocalStrings
import kotlinx.coroutines.launch

/**
 * What Maurice keeps on the member (`DomainsShelfView`): the conversations
 * he opened on his own, their domains — each opening its brief — and their
 * reading companions, each leading to its book.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MauriceScreen(nav: Navigator, openBrief: String? = null) {
    val theme = LocalMauriceTheme.current
    val strings = LocalStrings.current
    val api = LocalApi.current ?: return
    val chat = LocalChat.current ?: return
    val store = LocalDomains.current ?: return
    val scope = rememberCoroutineScope()
    val chatState by chat.state.collectAsState()
    val domainsState by store.state.collectAsState()
    var loaded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var brief by remember { mutableStateOf<Domain?>(null) }
    var resolving by remember { mutableStateOf<String?>(null) }

    suspend fun fetch() { store.load(); chat.loadConversations().join(); loaded = true }
    // Coming back from a page: a brief was saved, a conversation read.
    LaunchedEffect(nav.stack.size) { if (nav.stack.lastOrNull() is Route.Maurice || nav.stack.lastOrNull() is Route.Brief) fetch() }
    LaunchedEffect(openBrief, domainsState.rows) { if (openBrief != null && brief == null) brief = domainsState.row(openBrief) }

    val opened = chatState.conversations.filter { it.openedBy == "maurice" }
    val domains = domainsState.domains
    val companions = domainsState.companions

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { GridIconButton(Icons.AutoMirrored.Filled.ArrowBack, L("carnet.back")) { nav.pop() } }
        PullToRefreshBox(refreshing, onRefresh = { refreshing = true; scope.launch { fetch(); refreshing = false } }, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "title") { GridTitle("Maurice", L("carnet.maurice.kicker")) }
                if (!loaded && domains.isEmpty()) item(key = "loading") { GridLoading() }
                else if (domains.isEmpty() && companions.isEmpty() && opened.isEmpty()) item(key = "empty") {
                    GridRule()
                    Text(
                        L("carnet.maurice.empty"),
                        fontSize = 14.sp, color = theme.inkSoft, modifier = Modifier.padding(horizontal = PAGE, vertical = 28.dp),
                    )
                }
                if (opened.isNotEmpty()) {
                    item(key = "opened-label") { Section(L("carnet.maurice.opened_section")); GridRule() }
                    for (c in opened) item(key = "c-${c.id}") {
                        Row2("bubble.left", c.unread == true, c.title ?: "Maurice", null,
                            listOfNotNull(L("carnet.maurice.opened"), ageOf(c.createdAt, strings).takeIf { it.isNotEmpty() }, if (c.unread == true) L("carnet.maurice.unread") else null).joinToString(" · "),
                            chevron = true) { nav.push(Route.Conversation(c.id)) }
                        GridRule()
                    }
                }
                if (domains.isNotEmpty()) {
                    item(key = "domains-label") { Section(L("carnet.maurice.domains")); GridRule() }
                    for (d in domains) item(key = "d-${d.id}") {
                        val stamp = domainsState.overview[d.id]
                        val mine = stamp?.mine != false
                        val line = when {
                            !mine -> L("carnet.maurice.shared")
                            stamp?.brief == null -> L("carnet.maurice.no_brief")
                            else -> (if (stamp.brief!!.byMember) L("carnet.maurice.by_member") else L("carnet.maurice.by_maurice")) + ageOf(stamp.brief!!.updatedAt, strings).let { if (it.isEmpty()) "" else " · $it" }
                        }
                        Row2(d.icon ?: "book.closed", false, d.name, d.tagline.takeIf { it.isNotEmpty() }, line, chevron = mine, enabled = mine) { brief = d }
                        GridRule()
                    }
                }
                if (companions.isNotEmpty()) {
                    item(key = "companions-label") { Section(L("carnet.maurice.companions")); GridRule() }
                    for (c in companions) item(key = "k-${c.id}") {
                        val stamp = domainsState.overview[c.id]
                        val bookId = stamp?.bookId
                        Row2("book.pages", false, c.name, c.tagline.takeIf { it.isNotEmpty() },
                            if (stamp?.conversationId == null) L("carnet.maurice.no_conversation") else L("carnet.maurice.conversation_app"),
                            chevron = bookId != null, enabled = bookId != null && resolving == null) {
                            // A companion leads to its book's page, where the reading is.
                            if (bookId != null) nav.push(Route.Book(bookId, c.name, null))
                        }
                        GridRule()
                    }
                }
                if (domains.isNotEmpty() || companions.isNotEmpty()) item(key = "about") {
                    Text(
                        L("carnet.maurice.about"),
                        fontSize = 12.5.sp, color = theme.inkMute, modifier = Modifier.padding(horizontal = PAGE, vertical = 18.dp),
                    )
                }
            }
        }
    }

    brief?.let { d ->
        BriefSheet(d, store, chat,
            // "Talk about it": a blank page armed with the domain, here.
            onOpened = { brief = null; nav.push(Route.Conversation(null)) },
            onDismiss = { brief = null; if (nav.stack.lastOrNull() is Route.Brief) nav.pop() })
    }
}

@Composable
private fun Section(text: String) = GridLabel(text, 9.5.sp, modifier = Modifier.padding(horizontal = PAGE).padding(top = 18.dp, bottom = 8.dp))

/** A glyph in a square; a name, a line under it, a mono line. */
@Composable
private fun Row2(symbol: String, on: Boolean, title: String, subtitle: String?, line: String, chevron: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = PAGE, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(46.dp).background(if (on) theme.ink else theme.surfaceAlt).border(0.5.dp, if (on) theme.ink else theme.rule), contentAlignment = Alignment.Center) {
                Icon(DomainGlyphs.of(symbol), null, tint = if (on) theme.bg else theme.inkSoft, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.SemiBold, color = theme.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, fontSize = 12.5.sp, color = theme.inkMute, maxLines = 2, modifier = Modifier.padding(top = 2.dp)) }
                GridLabel(line, 9.5.sp, theme.inkSoft, modifier = Modifier.padding(top = 9.dp))
            }
            if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = theme.inkMute, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * A conversation with Maurice, here (`AskMauriceView`, `OpenedConversationView`):
 * the Maurice app's own thread, for a member without the app on this device
 * and for what Maurice opened on his own. [id] null: the page "Talk about
 * it" armed.
 */
@Composable
fun ConversationPage(session: Session, nav: Navigator, id: String?) {
    val chat = LocalChat.current ?: return
    val state by chat.state.collectAsState()
    val app by session.state.collectAsState()
    val household = app.current ?: return
    val member = app.activeMember ?: return
    LaunchedEffect(id) { if (id != null) chat.open(id) }
    // The thread is live while it is on screen.
    DisposableEffect(chat) { chat.resumeLive(); onDispose { chat.pauseLive() } }
    // The thread's Brief and Edit, opened here as the Maurice app opens them.
    val domainsNav = remember(chat) { DomainsNav() }
    val store = LocalDomains.current
    CompositionLocalProvider(LocalDomainsNav provides domainsNav) {
        ChatScreen(state, household, member, chat, onBack = { nav.pop() }, modifier = Modifier.statusBarsPadding())
        if (store != null) {
            domainsNav.briefFor?.let { d -> BriefSheet(d, store, chat, onOpened = { domainsNav.briefFor = null }) { domainsNav.briefFor = null } }
            domainsNav.editing?.let { d -> DomainEditor(d, store, chat, null, onClose = { domainsNav.editing = null }, onBack = { domainsNav.editing = null }) }
        }
    }
}
