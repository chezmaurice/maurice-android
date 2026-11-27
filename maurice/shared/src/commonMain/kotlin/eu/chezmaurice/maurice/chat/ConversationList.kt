package eu.chezmaurice.maurice.chat

import eu.chezmaurice.ui.accessibilityLabel
import eu.chezmaurice.ui.accessibilityHidden
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.chat.ChatState
import eu.chezmaurice.core.model.Conversation
import eu.chezmaurice.core.model.Household
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.maurice.absoluteUrl
import eu.chezmaurice.ui.Avatar
import eu.chezmaurice.ui.BoaterHat
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.hexColor
import eu.chezmaurice.ui.wordmark
import androidx.compose.runtime.collectAsState
import eu.chezmaurice.core.domains.DomainsState
import eu.chezmaurice.core.model.Domain
import eu.chezmaurice.maurice.domains.DomainMark
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.maurice.domains.LocalDomains
import eu.chezmaurice.maurice.domains.LocalDomainsNav
import eu.chezmaurice.ui.DomainGlyphs

@Composable
fun ConversationList(
    state: ChatState,
    household: Household,
    member: Member,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onLoadMore: () -> Unit,
    onDelete: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenGarden: (String) -> Unit = {},
) {
    val theme = LocalMauriceTheme.current
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, state.conversations.size) { if (nearEnd) onLoadMore() }
    val domainStore = LocalDomains.current
    val domains = domainStore?.state?.collectAsState()?.value ?: DomainsState()
    val nav = LocalDomainsNav.current

    Column(modifier.background(theme.surfaceAlt).statusBarsPadding()) {
        // The household and who is here.
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BoaterHat(size = 20.dp, color = hexColor(household.color, theme.ink))
            Spacer(Modifier.width(10.dp))
            Text(household.name, style = wordmark(18.sp), color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // The member's domains and companions; a badge for proposals not yet seen.
            if (domainStore != null) Box {
                // The badge's count is said with the button, as iOS's accessibilityValue.
                val badge = if (domains.unseen > 0) L("proposals.badge", domains.unseen) else null
                IconButton(onClick = nav::openList, modifier = Modifier.semantics { badge?.let { stateDescription = it } }) { Icon(DomainGlyphs.of("books.vertical"), L("domains.title"), tint = theme.inkSoft, modifier = Modifier.size(22.dp)) }
                if (domains.unseen > 0) {
                    Text(
                        domains.unseen.toString(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).accessibilityHidden().padding(top = 4.dp, end = 2.dp).clip(CircleShape).background(LocalAccent.current).padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            IconButton(onClick = onNew) { Icon(Icons.Default.Add, contentDescription = L("sidebar.new_conversation"), tint = theme.ink) }
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenSettings).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(member.displayName, member.avatarColor, absoluteUrl(household.serverUrl, member.avatarUrl), size = 28.dp)
            Spacer(Modifier.width(10.dp))
            Text(member.displayName, color = theme.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
            // What is left of the household's budget, shown only when it holds one; a tap opens Settings → Budget.
            eu.chezmaurice.maurice.budget.LocalBudget.current?.let { store ->
                val budget by store.state.collectAsState()
                if (budget.isLimited) {
                    val open = eu.chezmaurice.maurice.budget.LocalOpenBudget.current
                    val label = budget.leftPercent?.let { L("budget.value.left", it) } ?: L("budget.value.unlimited")
                    Box(Modifier.clip(CircleShape).clickable(onClick = open).accessibilityLabel(label).padding(6.dp)) {
                        eu.chezmaurice.maurice.budget.BudgetRing(budget.left, size = 18.dp)
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }
            Box {
                Icon(DomainGlyphs.of("gearshape"), L("settings.title"), tint = theme.inkMute, modifier = Modifier.size(18.dp))
                // An unread line in the register of interventions (Settings → My data).
                eu.chezmaurice.maurice.settings.InterventionDot(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-3).dp))
            }
        }
        SearchField(state.searchQuery, onSearch)
        if (state.searchResults != null || state.searching) {
            SearchResults(state, onOpen, Modifier.weight(1f))
            return@Column
        }
        // Your notes, then the ones kept with others.
        LocalGardens.current?.let { GardensSection(it, onOpenGarden) }
        Text(
            L("sidebar.conversations_header", member.displayName.uppercase()),
            fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 0.8.sp, color = theme.inkMute,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(state.conversations, key = { it.id }) { conversation ->
                ConversationRow(conversation, domains.row(conversation.mauriceId), selected = conversation.id == state.activeId, onOpen = { onOpen(conversation.id) }, onDelete = { onDelete(conversation.id) })
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, domain: Domain?, selected: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val theme = LocalMauriceTheme.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 1.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) theme.rule else theme.surfaceAlt)
                .clickable(onClick = onOpen)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (conversation.unread == true) Box(Modifier.size(7.dp).clip(CircleShape).background(theme.ink))
            // Bound to a domain or a companion: its mark; the everyday Maurice shows nothing.
            if (domain != null) DomainMark(domain, size = 26.dp, radius = 8.dp)
            Text(
                conversation.title?.takeIf { it.isNotBlank() } ?: L("sidebar.new_conversation"),
                color = theme.ink, fontSize = 14.sp,
                fontWeight = if (conversation.unread == true) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { menu = true }, modifier = Modifier.size(24.dp)) {
                Text("⋯", color = theme.inkMute)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(L("sidebar.delete")) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onSearch: (String) -> Unit) {
    val theme = LocalMauriceTheme.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp)).background(theme.rule).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = theme.inkMute, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        BasicTextField(
            value = query,
            onValueChange = onSearch,
            singleLine = true,
            textStyle = TextStyle(color = theme.ink, fontSize = 14.sp),
            cursorBrush = SolidColor(theme.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f).padding(vertical = 9.dp),
            decorationBox = { inner ->
                if (query.isEmpty()) Text(L("sidebar.search_placeholder"), color = theme.inkMute, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            },
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onSearch("") }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, L("sidebar.search_clear"), tint = theme.inkMute, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun SearchResults(state: ChatState, onOpen: (String) -> Unit, modifier: Modifier) {
    val theme = LocalMauriceTheme.current
    val results = state.searchResults.orEmpty()
    LazyColumn(modifier) {
        when {
            state.searching && results.isEmpty() -> item { Hint(L("sidebar.search_searching")) }
            results.isEmpty() -> item { Hint(L("sidebar.search_none")) }
        }
        items(results, key = { it.conversation.id }) { hit ->
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)
                    .clip(RoundedCornerShape(8.dp)).clickable { onOpen(hit.conversation.id) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    hit.conversation.title?.takeIf { it.isNotBlank() } ?: L("sidebar.new_conversation"),
                    color = theme.ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (hit.snippet.isNotBlank()) {
                    Text(highlighted(hit.snippet, theme.ink), color = theme.inkSoft, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = LocalMauriceTheme.current.inkMute, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
}

/** The server wraps matched words in ⟦ ⟧: they read in bold, in full ink. */
internal fun highlighted(snippet: String, ink: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var rest = snippet
    while (true) {
        val open = rest.indexOf('⟦')
        val close = if (open >= 0) rest.indexOf('⟧', open) else -1
        if (open < 0 || close < 0) { append(rest); break }
        append(rest.substring(0, open))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = ink)) { append(rest.substring(open + 1, close)) }
        rest = rest.substring(close + 1)
    }
}
