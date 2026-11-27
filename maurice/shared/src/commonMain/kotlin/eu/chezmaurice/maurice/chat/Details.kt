package eu.chezmaurice.maurice.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.chezmaurice.core.chat.ChatController
import eu.chezmaurice.core.chat.ChatState
import eu.chezmaurice.core.chat.ConversationUsage
import eu.chezmaurice.core.chat.UsageFormat
import eu.chezmaurice.core.chat.saved
import eu.chezmaurice.maurice.consent.LocalAppLanguage
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.model.canRemoveOthers
import eu.chezmaurice.core.model.formatServerDate
import eu.chezmaurice.maurice.absoluteUrl
import eu.chezmaurice.ui.Avatar
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import androidx.compose.runtime.collectAsState
import eu.chezmaurice.maurice.domains.LocalDomains
import kotlinx.coroutines.launch

/**
 * The conversation's details — `ConversationDetailsSheet`: its title, who it
 * is with, the domain it is filed under (alone with Maurice), the people of a
 * room and whom to add, leaving the room.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(state: ChatState, me: Member, serverUrl: String, controller: ChatController, onDismiss: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val conversation = state.active ?: return
    val room = state.participants.size > 1
    var title by remember(conversation.id) { mutableStateOf(conversation.title.orEmpty()) }
    var addable by remember { mutableStateOf<List<Member>?>(null) }
    val scope = rememberCoroutineScope()
    val rows = LocalDomains.current?.state?.collectAsState()?.value?.rows.orEmpty()
    val ownDomains = rows.filter { it.isDomainOf(me.id) }

    // Fully open from the start: a half-open sheet stays under the keyboard.
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = theme.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section(L("chat.title_field"))
            // Only its creator may rename a conversation (the server says 404 to others).
            if (conversation.userId == null || conversation.userId == me.id) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val changed = title.isNotBlank() && title.trim() != conversation.title.orEmpty()
                    OutlinedTextField(
                        title, { title = it }, placeholder = { Text(L("chat.title_placeholder")) }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (changed) controller.rename(conversation.id, title.trim()) }),
                    )
                    TextButton(onClick = { controller.rename(conversation.id, title.trim()) }, enabled = changed) { Text(L("common.save"), color = if (changed) theme.ink else theme.inkMute) }
                }
            } else {
                Text(conversation.title ?: L("sidebar.new_conversation"), fontSize = 16.sp, color = theme.ink)
            }

            Section(L("chat.details"))
            val with = rows.firstOrNull { it.id == conversation.mauriceId }?.name ?: L("details.everyday")
            InfoRow(L("chat.details.with"), with)
            formatServerDate(conversation.createdAt)?.let { InfoRow(L("chat.details.created"), it) }
            formatServerDate(conversation.lastMessageAt ?: conversation.updatedAt)?.let { InfoRow(L("chat.details.last_message"), it) }
            InfoRow(L("chat.details.messages"), (conversation.messageCount ?: state.messages.size).toString())

            // Filing: only alone with Maurice, and only among the member's own domains.
            if (!room && ownDomains.isNotEmpty()) {
                Section(L("chat.details.domain"))
                Choice(L("chat.details.domain.none"), selected = ownDomains.none { it.id == conversation.mauriceId }) { scope.launch { controller.setDomain(null) } }
                for (d in ownDomains) Choice(d.name, selected = d.id == conversation.mauriceId) { scope.launch { controller.setDomain(d.id) } }
                if (conversation.mauriceBoundBy == "auto") Text(L("chat.details.domain.auto"), fontSize = 12.sp, color = theme.inkMute)
            }

            // The same switch as the coin under each reply: the whole conversation's meter.
            val showCost = LocalTurnCost.current?.on?.collectAsState()?.value == true
            if (showCost) UsageSection(ConversationUsage.of(state.messages.filter { it.isAssistant }.map { it.usage }))

            if (room) {
                Section(L("chat.details.people"))
                for (p in state.participants) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Avatar(p.displayName, p.avatarColor, absoluteUrl(serverUrl, p.avatarUrl), size = 28.dp)
                        Text(p.displayName, fontSize = 15.sp, color = theme.ink, modifier = Modifier.weight(1f))
                        // Anyone but you may be taken out (added by mistake, say) — by whoever
                        // opened the room, or the household's admin: the server refuses
                        // anyone else (403 not_owner, 26 November 2026).
                        if (p.memberId != me.id && canRemoveOthers(state.participants, me.id, me.role == eu.chezmaurice.core.model.Role.Admin)) {
                            val label = L("chat.remove_participant")
                            IconButton(onClick = { controller.removeParticipant(p.memberId) }, modifier = Modifier.size(30.dp).semantics { contentDescription = label }) {
                                // minus.circle.fill
                                Box(Modifier.size(20.dp).clip(CircleShape).background(Color.Red.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(width = 10.dp, height = 2.dp).background(Color.White))
                                }
                            }
                        }
                    }
                }
            }
            // Adding someone turns a conversation into a room.
            TextButton(onClick = { scope.launch { addable = controller.addable() } }) { Text(L("details.add_member"), color = theme.ink) }
            addable?.let { people ->
                if (people.isEmpty()) Text(L("chat.everyone_here"), fontSize = 13.sp, color = theme.inkMute)
                for (m in people) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                            controller.addParticipant(m.id); addable = people - m
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(m.displayName, m.avatarColor, absoluteUrl(serverUrl, m.avatarUrl), size = 28.dp)
                        Text(m.displayName, fontSize = 15.sp, color = theme.ink, modifier = Modifier.weight(1f))
                        Text("+", fontSize = 18.sp, color = theme.inkSoft)
                    }
                }
            }
            if (room) {
                HorizontalDivider(color = theme.rule)
                TextButton(onClick = { controller.leave(); onDismiss() }) { Text(L("chat.leave_room"), color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), fontSize = 11.sp, letterSpacing = 0.6.sp, color = LocalMauriceTheme.current.inkMute, modifier = Modifier.padding(top = 8.dp))
}

/**
 * *Cost and tokens* (`usageSection`): total cost first, then what the cache
 * did for it, the token split, and which models did the work. Unpriced turns
 * fall back to token volume rather than a false "0 €".
 */
@Composable
private fun UsageSection(u: ConversationUsage) {
    val theme = LocalMauriceTheme.current
    val language = LocalAppLanguage.current
    fun money(v: Double) = UsageFormat.money(v, language)
    Section(L("chat.details.usage"))
    if (u.turns == 0) {
        Text(L("chat.details.usage.empty"), fontSize = 13.sp, color = theme.inkMute)
        return
    }
    val cost = u.cost
    if (cost != null) {
        InfoRow(L("chat.details.usage.total"), money(cost))
        if (u.pricedTurns < u.turns) InfoRow(L("chat.details.usage.priced_turns"), "${u.pricedTurns} / ${u.turns}")
        u.averageCost?.let { InfoRow(L("chat.details.usage.average"), money(it)) }
        u.costliestTurn?.takeIf { u.pricedTurns > 1 }?.let { InfoRow(L("chat.details.usage.costliest"), money(it)) }
        u.costUncached?.let { uncached ->
            InfoRow(L("chat.details.usage.without_cache"), money(uncached))
            u.saved?.takeIf { it > 0 }?.let { InfoRow(L("chat.details.usage.saved"), money(it)) }
        }
    } else {
        InfoRow(L("chat.details.usage.total"), L("chat.details.usage.unpriced"))
    }
    InfoRow(L("chat.details.usage.turns"), if (u.unmetered > 0) L("chat.details.usage.turns_unmetered", u.turns, u.unmetered) else "${u.turns}")
    InfoRow(L("chat.details.usage.rounds"), "${u.rounds}")
    u.cacheHitRate?.let { InfoRow(L("chat.details.usage.cache"), UsageFormat.percent(it)) }
    InfoRow(L("chat.details.usage.prompt"), L("chat.details.usage.prompt_split", UsageFormat.tokens(u.input), UsageFormat.tokens(u.cacheRead), UsageFormat.tokens(u.cacheWrite)))
    InfoRow(L("chat.details.usage.reply"), UsageFormat.tokens(u.output))
    InfoRow(L("chat.details.usage.tokens"), UsageFormat.tokens(u.totalTokens))
    for (share in u.models) {
        InfoRow(share.model, L("chat.details.usage.model_share", share.turns, share.cost?.let(::money) ?: L("chat.details.usage.unpriced")))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val theme = LocalMauriceTheme.current
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 14.sp, color = theme.inkSoft, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = theme.ink)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onSelect, colors = RadioButtonDefaults.colors(selectedColor = theme.ink, unselectedColor = theme.inkMute))
        Text(label, fontSize = 15.sp, color = theme.ink)
    }
}

private val REASONS = listOf("spam", "harassment_or_bullying", "sexual_content", "child_safety", "other")

/** Why a reply of Maurice's is reported (`AIReplyReason`). */
private val AI_REASONS = listOf("harmful_or_misleading", "sexual_content", "harassment_or_bullying", "child_safety", "other")

/** The publisher, for what is about the app itself (`SafetyLinks.publisherContact`). */
private const val PUBLISHER_CONTACT = "safety@chezmaurice.eu"

/** A small flag, as SF Symbols' "flag": report this reply. */
val FlagIcon: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder("flag", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                "M5,3 L6.6,3 L6.6,21 L5,21 Z M7.4,4 C10,2.6 12,5.6 15,4.6 C16.6,4.1 18,3.6 19,3.4 L19,13 C18,13.2 16.6,13.7 15,14.2 C12,15.2 10,12.2 7.4,13.6 Z"
            ).toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
        )
    }.build()
}

/**
 * Report a reply of Maurice's, in any conversation, private ones included
 * (`ReportSheet`, `ai_reply`): what is shared and with whom is said before
 * sending — the reply and the question before it go to the household's admin
 * — and the publisher's address after. Child safety also points to the authorities.
 */
@Composable
fun AiReplyReportDialog(messageId: String, controller: ChatController, onDismiss: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf(AI_REASONS.first()) }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 440.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(theme.surface)
                    .verticalScroll(rememberScrollState()).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(L("report.ai.title"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = theme.ink)
                if (sent) {
                    Text(L("report.ai.sent"), fontSize = 14.sp, color = theme.ink)
                    SelectionContainer { Text(L("report.ai.publisher", PUBLISHER_CONTACT), fontSize = 12.5.sp, color = theme.inkSoft) }
                    if (reason == "child_safety") {
                        Text(L("report.ai.authority.header"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = theme.ink)
                        Text(L("report.ai.authority"), fontSize = 13.sp, color = theme.inkSoft)
                        Text("NCMEC CyberTipline (US)", fontSize = 13.sp, color = theme.ink, modifier = Modifier.clickable { uri.openUri("https://report.cybertip.org") })
                        Text("Child Focus (BE)", fontSize = 13.sp, color = theme.ink, modifier = Modifier.clickable { uri.openUri("https://www.stopchildporno.be") })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(L("report.ai.done"), color = theme.ink) }
                    }
                } else {
                    Text(L("report.ai.disclosure"), fontSize = 13.5.sp, color = theme.inkSoft)
                    Text(L("report.ai.reason"), fontSize = 12.sp, color = theme.inkMute)
                    for (r in AI_REASONS) Choice(L("report.ai.reason.$r"), reason == r) { reason = r }
                    OutlinedTextField(note, { note = it }, label = { Text(L("report.ai.note")) }, placeholder = { Text(L("report.ai.note.placeholder")) }, maxLines = 4, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(L("report.ai.cancel"), color = theme.inkSoft) }
                        Spacer(Modifier.size(4.dp))
                        TextButton(enabled = !sending, onClick = {
                            sending = true
                            scope.launch {
                                sent = controller.report("ai_reply", messageId, reason, note.ifEmpty { null })
                                sending = false
                            }
                        }) { Text(L("report.ai.send"), color = theme.ink) }
                    }
                }
            }
        }
    }
}

/**
 * Report a message of a shared room to the server's operator — `ReportSheet`.
 * A child-safety report also points to the authorities, who can act where
 * the operator can't.
 */
@Composable
fun ReportDialog(messageId: String, controller: ChatController, onDismiss: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf(REASONS.first()) }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 440.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(theme.surface)
                    .verticalScroll(rememberScrollState()).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(L("report.title"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = theme.ink)
                if (sent) {
                    Text(L("report.sent"), fontSize = 14.sp, color = theme.ink)
                    if (reason == "child_safety") {
                        Text(L("report.authority_title"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = theme.ink)
                        Text(L("report.authority_body"), fontSize = 13.sp, color = theme.inkSoft)
                        Text(L("report.authority_ncmec"), fontSize = 13.sp, color = theme.ink, modifier = Modifier.clickable { uri.openUri("https://report.cybertip.org/") })
                        Text(L("report.authority_elsewhere"), fontSize = 12.sp, color = theme.inkMute)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(L("common.done"), color = theme.ink) }
                    }
                } else {
                    Text(L("report.reason"), fontSize = 12.sp, color = theme.inkMute)
                    for (r in REASONS) Choice(L("report.reason.$r"), reason == r) { reason = r }
                    OutlinedTextField(note, { note = it }, label = { Text(L("report.note")) }, placeholder = { Text(L("report.note_placeholder")) }, maxLines = 4, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(L("common.cancel"), color = theme.inkSoft) }
                        Spacer(Modifier.size(4.dp))
                        TextButton(enabled = !sending, onClick = {
                            sending = true
                            scope.launch {
                                val ok = controller.report("message", messageId, reason, note.ifEmpty { null })
                                sending = false
                                if (ok) { if (reason == "child_safety") sent = true else onDismiss() }
                            }
                        }) { Text(L("report.submit"), color = theme.ink) }
                    }
                }
            }
        }
    }
}
