package eu.chezmaurice.maurice.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.DisposableEffect
import eu.chezmaurice.core.dictation.Recording
import eu.chezmaurice.maurice.platform.Recorder
import eu.chezmaurice.maurice.platform.rememberMicPermission
import eu.chezmaurice.maurice.platform.rememberRecorder
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.text.font.FontWeight
import eu.chezmaurice.core.model.Participant
import eu.chezmaurice.core.model.ConversationEntry
import eu.chezmaurice.core.composer.ItemType
import eu.chezmaurice.ui.hexColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.layout.ContentScale
import eu.chezmaurice.core.media.PhotoSize
import eu.chezmaurice.maurice.platform.normalizeToJpeg
import eu.chezmaurice.maurice.platform.rememberPhotoPicker
import eu.chezmaurice.maurice.platform.rememberCamera
import eu.chezmaurice.maurice.platform.rememberFilePicker
import eu.chezmaurice.maurice.platform.rememberImageClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import eu.chezmaurice.ui.MauriceIcons
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import eu.chezmaurice.maurice.platform.plainTextClip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import eu.chezmaurice.maurice.consent.AiDeclinedBanner
import eu.chezmaurice.maurice.consent.LocalAiConsent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import eu.chezmaurice.core.chat.ChatController
import eu.chezmaurice.core.chat.ChatState
import eu.chezmaurice.core.chat.Streaming
import eu.chezmaurice.core.chat.Piece
import eu.chezmaurice.core.chat.Snippet
import eu.chezmaurice.core.chat.ToolStep
import eu.chezmaurice.core.chat.answerCards
import eu.chezmaurice.core.chat.tidyPartialMarkdown
import eu.chezmaurice.core.chat.withoutReviewFooter
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import eu.chezmaurice.core.model.Household
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.model.Message
import eu.chezmaurice.ui.BoaterHat
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.ui.wordmark
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import eu.chezmaurice.maurice.domains.DomainGreeting
import eu.chezmaurice.maurice.domains.DomainPastilles
import eu.chezmaurice.maurice.domains.LocalDomains
import eu.chezmaurice.maurice.domains.ModelPill
import eu.chezmaurice.maurice.domains.pastilles
import androidx.compose.material.icons.filled.Add
import eu.chezmaurice.maurice.composer.AddContextSheet
import eu.chezmaurice.maurice.composer.ContextTray
import eu.chezmaurice.maurice.composer.ConversationToolsSheet
import eu.chezmaurice.maurice.composer.LocalComposer
import eu.chezmaurice.ui.DomainGlyphs
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle

@Composable
fun ChatScreen(
    state: ChatState,
    household: Household,
    member: Member,
    controller: ChatController,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val theme = LocalMauriceTheme.current
    // Changes twice a turn; the text itself is read only by the reply row.
    val hasLive by controller.hasLive.collectAsState()
    var showDetails by remember { mutableStateOf(false) }
    var showSuggestions by remember { mutableStateOf(false) }
    // The message being reported, when the report dialog is open.
    var reporting by remember { mutableStateOf<String?>(null) }
    var reportingReply by remember { mutableStateOf<String?>(null) }
    val blockScope = rememberCoroutineScope()

    // Where the thread scrolls (ThreadScroll): a question sent goes to the
    // top and the reply writes itself under it. Kept across the list's
    // mounts: a first question is armed before the thread exists.
    val density = LocalDensity.current
    val scroll = remember(density) {
        with(density) { ThreadScroll(air = PIN_AIR.toPx(), bottomPadding = THREAD_PADDING.toPx()) }
    }
    // Opening or switching a conversation: to its end, following. The
    // conversation a first question has just created keeps it pinned.
    val shownConversation = remember { arrayOf(state.activeId) }
    LaunchedEffect(state.activeId) {
        val from = shownConversation[0]
        if (from != state.activeId) {
            scroll.opened(state.activeId, created = from == null && state.messages.isNotEmpty())
            shownConversation[0] = state.activeId
        }
    }

    Column(modifier.background(theme.bg).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L("chat.back_to_conversations"), tint = theme.ink) }
            } else Spacer(Modifier.size(12.dp))
            Text(
                state.active?.title ?: "",
                color = theme.ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            // The member's domains this conversation has to do with.
            val domainStore = LocalDomains.current
            if (domainStore != null) {
                val domains by domainStore.state.collectAsState()
                val blocks = state.messages.flatMap { it.data.orEmpty() }
                val marks = remember(domains.rows, state.active?.mauriceId, state.active?.mauriceBoundBy, blocks) {
                    pastilles(domains.rows, member.id, state.active?.mauriceId, state.active?.mauriceBoundBy == "auto", blocks)
                }
                DomainPastilles(marks, controller, canBind = state.activeId != null && state.participants.size <= 1)
            }
            // What Carnet could keep from the exchange: never in a room, never for a guest.
            state.active?.takeIf { state.participants.size <= 1 && member.role != eu.chezmaurice.core.model.Role.Guest }?.let { conversation ->
                SuggestionsMark(conversation) { showSuggestions = true }
            }
            if (state.active != null) {
                IconButton(onClick = { showDetails = true }) { Icon(Icons.Default.MoreVert, L("chat.details"), tint = theme.inkSoft) }
            }
        }
        if (showDetails) DetailsSheet(state, member, household.serverUrl, controller) { showDetails = false }
        state.activeId?.takeIf { showSuggestions }?.let { id -> SuggestionsDrawer(id, controller, household.serverUrl) { showSuggestions = false } }
        reporting?.let { id -> ReportDialog(id, controller) { reporting = null } }
        reportingReply?.let { id -> AiReplyReportDialog(id, controller) { reportingReply = null } }
        HorizontalDivider(color = theme.rule)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loadingMessages -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), color = theme.inkMute, strokeWidth = 2.dp)
                state.messages.isEmpty() && !hasLive -> {
                    // A conversation of a domain or a companion greets with it; it is
                    // armed before the first send, bound after.
                    val bound = LocalDomains.current?.state?.collectAsState()?.value?.row(state.mauriceId)
                    if (bound != null) DomainGreeting(bound, member.id, Modifier.align(Alignment.Center).verticalScroll(rememberScrollState()))
                    else Greeting(member, Modifier.align(Alignment.Center))
                }
                else -> ChatThread(
                    scroll = scroll,
                    conversation = state.activeId,
                    messages = state.messages,
                    memberId = member.id,
                    turnLive = hasLive || state.turnRunning,
                    rowsAfter = if (hasLive || state.othersSummoned) 1 else 0,
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        val last = message.id == state.messages.lastOrNull()?.id
                        MessageRow(
                            message, household.serverUrl, mine = message.authorId == member.id,
                            onRegenerate = if (last && message.isAssistant && !hasLive) controller::regenerate else null,
                            // As on iOS: offered in a 1:1 thread that ends with the member's own message.
                            onReplay = if (last && !message.isAssistant && message.authorId == member.id && !hasLive &&
                                !state.turnRunning && state.participants.size <= 1 && !message.id.startsWith("local-")) controller::regenerate else null,
                            author = if (state.participants.size > 1) state.participants.firstOrNull { it.memberId == message.authorId } else null,
                            trail = state.trails[message.id].orEmpty(),
                            accent = hexColor(member.avatarColor),
                            onSendText = { controller.send(it) },
                            decideFact = controller::decideFact,
                            onReport = { reporting = message.id },
                            onReportReply = if (message.isAssistant && !message.id.startsWith("local-")) ({ reportingReply = message.id }) else null,
                            onBlock = { message.authorId?.let { blockScope.launch { controller.block(it) } } },
                        )
                    }
                    // The reply being written.
                    if (hasLive) item(key = "live") { LiveReply(controller, household.serverUrl) }
                    // Someone else summoned Maurice here; his reply is on its way.
                    else if (state.othersSummoned) item(key = "summoned") {
                        Column(COLUMN.padding(vertical = 10.dp)) { ActivityLine(Streaming(thinking = true)) }
                    }
                }
            }
        }

        state.error?.let { error ->
            val strings = LocalStrings.current
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (strings.has(error)) strings(error) else error, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = controller::dismissError, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        // What Maurice reads with this conversation's turns.
        val composer = LocalComposer.current
        var trayOpen by remember(state.activeId) { mutableStateOf(false) }
        var addingContext by remember { mutableStateOf(false) }
        var showTools by remember { mutableStateOf(false) }
        val composerState = composer?.state?.collectAsState()?.value
        val hasContext = composerState?.hasContext == true
        // The entry a new conversation is held from (Carnet's Maurice
        // button), until the first message: shown in the bubble as a paste is.
        val heldEntry = state.activeEntry?.takeIf { state.messages.isEmpty() && state.activeId != null }
        val heldInContext = heldEntry != null &&
            composerState?.items.orEmpty().any { it.type == ItemType.Fiche && it.rawId == "${heldEntry.ref}-fiche" }
        val releaseScope = rememberCoroutineScope()
        // + → Files: anything the device holds, as iOS's importer takes any item.
        val pickFile = rememberFilePicker(multiple = false, mimeTypes = emptyList()) { files ->
            val file = files.firstOrNull() ?: return@rememberFilePicker
            releaseScope.launch { composer?.addUploaded(file) }
        }
        val canDictate by controller.canDictate.collectAsState()
        LaunchedEffect(controller) { controller.probeDictation() }
        // Nothing goes to Maurice without a yes to the AI providers; after a
        // no, the banner says why, one tap from changing it. Posting to the
        // people of a room stays open.
        val aiConsent = LocalAiConsent.current
        val consent = aiConsent?.state?.collectAsState()?.value
        if (consent?.declined == true) {
            AiDeclinedBanner(onReview = aiConsent::review, modifier = Modifier.padding(horizontal = 12.dp).padding(top = 8.dp))
        }
        // Received read-only from another household: no composer, the band and a way on.
        val receivedFrom = state.activeReceivedFrom
        if (receivedFrom != null) ReceivedBand(receivedFrom, hexColor(member.avatarColor), onContinue = { controller.startFromReceived() })
        else Composer(
            mayAskMaurice = consent?.mayUseAI != false,
            streaming = state.turnRunning,
            canDictate = canDictate,
            transcribe = controller::transcribe,
            readArticle = controller::readArticle,
            room = state.participants.size > 1,
            accent = hexColor(member.avatarColor),
            onPost = { text, images -> scroll.sent(); controller.send(text, images = images, summon = false) },
            onSend = { text, images -> scroll.sent(); controller.send(text, images = images) },
            onStop = controller::stop,
            modelPill = { ModelPill(state.mauriceId, enabled = !state.turnRunning) },
            onAddContext = if (composer != null) ({ trayOpen = true; addingContext = true }) else null,
            onOpenTools = if (composer != null && state.activeId != null) ({ showTools = true }) else null,
            onAddFile = if (composer != null) pickFile else null,
            hasContext = hasContext,
            heldEntry = heldEntry,
            heldInContext = heldInContext,
            // Taken off like a paste: the conversation is then held from
            // nothing, and the fiche leaves its context.
            onReleaseEntry = { releaseScope.launch { if (controller.releaseEntry()) composer?.reload() } },
            trayOpen = trayOpen,
            onToggleTray = { trayOpen = !trayOpen },
            onSent = { trayOpen = false },
            tray = {
                if (trayOpen && composer != null) ContextTray(composer, onDone = { trayOpen = false }, onAddMore = { addingContext = true }, modifier = Modifier.padding(bottom = 8.dp), maxHeight = 300.dp)
            },
        )
        if (composer != null) {
            if (addingContext) AddContextSheet(composer) { addingContext = false }
            val id = state.activeId
            if (showTools && id != null) ConversationToolsSheet(id, composer) { showTools = false }
        }
    }
}

/**
 * Where the composer would be, in a conversation received read-only from
 * another household with an entry given here (specs/cross-household-share.md,
 * E; `ReceivedConversationBand`): where it came from, and the way to go on
 * talking — a new conversation held from the same entry.
 */
@Composable
private fun ReceivedBand(household: String, accent: Color, onContinue: () -> Unit) {
    val theme = LocalMauriceTheme.current
    Column(
        Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp).fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)).background(theme.surface)
            .border(0.5.dp, theme.rule, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(DomainGlyphs.of("tray.full"), null, tint = theme.ink, modifier = Modifier.size(15.dp))
            Text(if (household.isEmpty()) L("chat.received.band_unnamed") else L("chat.received.band", household), fontSize = 13.sp, color = theme.ink)
        }
        Row(
            Modifier.clickable(onClick = onContinue), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = legible(accent, theme.isDark)
            Icon(DomainGlyphs.of("bubble.left.and.bubble.right"), null, tint = tint, modifier = Modifier.size(15.dp))
            Text(L("chat.received.continue"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tint)
        }
    }
}

@Composable
private fun Greeting(member: Member, modifier: Modifier) {
    val theme = LocalMauriceTheme.current
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BoaterHat(size = 40.dp, color = theme.inkMute)
        Spacer(Modifier.height(16.dp))
        Text(L("chat.greeting", member.displayName), style = wordmark(24.sp), color = theme.ink)
    }
}

/** The reading column: as on iOS, prose is kept to a comfortable width. */
private val COLUMN = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 20.dp)

@Composable
private fun MessageRow(
    message: Message,
    serverUrl: String,
    mine: Boolean,
    onRegenerate: (() -> Unit)?,
    onReplay: (() -> Unit)?,
    /** In a room, who wrote it — named, in their colour. */
    author: Participant? = null,
    /** The tools this reply used, if it was watched in this session. */
    trail: List<ToolStep> = emptyList(),
    accent: Color = Color.Unspecified,
    onSendText: (String) -> Unit = {},
    decideFact: suspend (String, Boolean) -> String? = { _, _ -> null },
    /** Report / block — offered on others' messages in a room. */
    onReport: () -> Unit = {},
    onBlock: () -> Unit = {},
    /** Report a reply of Maurice's (`ai_reply`): to the household's admin. */
    onReportReply: (() -> Unit)? = null,
) {
    val theme = LocalMauriceTheme.current
    if (message.isAssistant) {
        val blocks = message.data.orEmpty()
        Column(COLUMN.padding(vertical = 10.dp)) {
            TurnSummaryLine(trail, blocks, serverUrl, running = false)
            MessagePieces(withoutReviewFooter(message.content, blocks), serverUrl, theme.surface)
            val cards = blocks.answerCards()
            if (cards.isNotEmpty()) AnswerCards(cards, serverUrl, accent, onSend = onSendText, decideFact = decideFact)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                CopyButton(message.content)
                if (onRegenerate != null) {
                    IconButton(onClick = onRegenerate, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Refresh, L("chat.regenerate"), tint = theme.inkMute, modifier = Modifier.size(18.dp))
                    }
                }
                // Any reply of Maurice's can be reported, private conversations included.
                if (onReportReply != null) {
                    IconButton(onClick = onReportReply, modifier = Modifier.size(32.dp)) {
                        Icon(FlagIcon, L("report.ai.title"), tint = theme.inkMute, modifier = Modifier.size(15.dp))
                    }
                }
                // What the turn cost, when the member turned the meter on in Settings.
                val showCost = LocalTurnCost.current?.on?.collectAsState()?.value == true
                if (showCost) message.usage?.let { TurnUsageFooter(it) }
            }
        }
    } else {
        // As on iOS: every human's message sits on the right; Maurice's reply
        // is the text on the page. In a room the sender is named above.
        Column(COLUMN.padding(vertical = 6.dp), horizontalAlignment = Alignment.End) {
            Column(
                Modifier.widthIn(max = 560.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(theme.surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (author != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(author.displayName, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = legible(hexColor(author.avatarColor), theme.isDark))
                        if (!mine) SafetyMenu(author.displayName, onReport, onBlock)
                    }
                    Spacer(Modifier.size(2.dp))
                }
                // What was pasted or shared rides as cards, as it did in the composer.
                MessagePieces(message.content, serverUrl, theme.bg)
            }
            // No reply followed (an error, a stop before any word): play the turn again.
            if (onReplay != null) {
                Row(
                    Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onReplay).padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Refresh, null, tint = theme.inkMute, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.size(5.dp))
                    Text(L("chat.regenerate"), fontSize = 12.sp, color = theme.inkMute)
                }
            }
        }
    }
}

/**
 * A message's text and the cards it carries (a long paste, a shared
 * article), in the order they come — wherever they sit, whoever wrote it,
 * as ReplyDocument draws them.
 */
@Composable
private fun MessagePieces(content: String, serverUrl: String, cardBackground: Color) {
    val pieces = remember(content) { Snippet.pieces(content) }
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (piece in pieces) when (piece) {
            is Piece.Text -> SelectionContainer { ReplyText(piece.text, serverUrl) }
            is Piece.Card -> when (val card = piece.snippet) {
                is Snippet.Paste -> SnippetCard(L("pasted.title"), if (card.lines == 1) L("pasted.one_line") else L("pasted.lines", card.lines), onRemove = null, loading = false, background = cardBackground)
                is Snippet.Article -> SnippetCard(
                    card.title.ifEmpty { card.url },
                    listOf(card.source, if (card.wordCount == 0) L("article.link_only") else L("article.words", card.wordCount)).filter { it.isNotEmpty() }.joinToString(" · "),
                    onRemove = null, loading = false, background = cardBackground,
                    onOpen = card.url.takeIf { it.isNotEmpty() }?.let { u -> { uri.openUri(u) } },
                )
            }
        }
    }
}

/** Report the message, or block its author — only in a room, on others' messages. */
@Composable
private fun SafetyMenu(name: String, onReport: () -> Unit, onBlock: () -> Unit) {
    val theme = LocalMauriceTheme.current
    var open by remember { mutableStateOf(false) }
    Box {
        Text("  ⋯", fontSize = 13.sp, color = theme.inkMute, modifier = Modifier.clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(L("report.message")) }, onClick = { open = false; onReport() })
            DropdownMenuItem(text = { Text(L("report.block", name), color = MaterialTheme.colorScheme.error) }, onClick = { open = false; onBlock() })
        }
    }
}

/** "Copy", then "Copied" for a moment — the Markdown as written. */
@Composable
private fun CopyButton(text: String) {
    val theme = LocalMauriceTheme.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    IconButton(
        onClick = {
            scope.launch {
                clipboard.setClipEntry(plainTextClip(text))
                copied = true
                delay(1_500)
                copied = false
            }
        },
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            if (copied) Icons.Default.Check else MauriceIcons.ContentCopy,
            contentDescription = L(if (copied) "chat.copied" else "chat.copy"),
            tint = theme.inkMute, modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * The reply being written — the only part of the screen that reads each
 * token. The text is typed out at a steady pace rather than in bursts
 * (`StreamingRow.swift`): 120 characters a second once caught up, a third of
 * any backlog every tenth of a second. Once the turn is stored, it finishes
 * typing, then tells the controller the message can take its place.
 */
@Composable
private fun LiveReply(controller: ChatController, serverUrl: String) {
    val live by controller.live.collectAsState()
    val streaming = live ?: return
    val current by rememberUpdatedState(streaming)
    var shown by remember { mutableIntStateOf(0) }
    val isImage = streaming.text.trimStart().startsWith("![")

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val target = current.text.length
                val dt = if (last == 0L) 1.0 / 60 else minOf(0.25, (now - last) / 1e9)
                last = now
                if (shown < target) {
                    val behind = target - shown
                    shown = minOf(target, shown + maxOf(1, ((120.0 + behind * 3.0) * dt).toInt()))
                } else if (shown > target) {
                    shown = target // the text was replaced (an image, a resume)
                }
            }
            val done = current.finishedId
            if (done != null && (shown >= current.text.length || current.text.trimStart().startsWith("![")) ) {
                controller.revealed(done)
            }
        }
    }

    Column(COLUMN.padding(vertical = 10.dp)) {
        if (streaming.steps.isNotEmpty() || streaming.data.isNotEmpty()) {
            val running = streaming.finishedId == null
            TurnSummaryLine(streaming.steps, streaming.data, serverUrl, running = running, now = if (running) activityLabel(streaming) else null)
        } else if (streaming.finishedId == null) ActivityLine(streaming)
        val visible = when {
            isImage -> streaming.text
            shown >= streaming.text.length && streaming.finishedId != null -> streaming.text
            else -> tidyPartialMarkdown(streaming.text.take(shown))
        }
        if (visible.isNotEmpty()) ReplyText(withoutReviewFooter(visible, streaming.data), serverUrl)
    }
}

/**
 * One line of what Maurice is doing: thinking, a tool, a lost connection —
 * then the pulse chosen in Settings (`TurnActivityRow` with no step yet).
 */
@Composable
private fun ActivityLine(streaming: Streaming) {
    val theme = LocalMauriceTheme.current
    val label = activityLabel(streaming) ?: return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        Text(label, fontSize = 12.5.sp, color = theme.inkSoft, maxLines = 1)
        Spacer(Modifier.size(8.dp))
        ThinkingPulse(color = theme.inkSoft)
    }
}

/** What runs now: the tool at work, or the phase (thinking, reconnecting). */
@Composable
private fun activityLabel(streaming: Streaming): String? {
    val strings = LocalStrings.current
    val running = streaming.steps.filter { it.running }
    return when {
        streaming.reconnecting -> L("chat.activity.reconnecting")
        running.isNotEmpty() -> running.joinToString(" · ") { strings.toolLabel(it.tool, running = true) }
        streaming.imagePrompt != null -> streaming.imagePrompt
        streaming.thinking || streaming.text.isEmpty() -> L("chat.activity.thinking")
        else -> null
    }
}

@Composable
private fun Composer(
    /** False after a no to the AI providers: nothing is sent to Maurice. */
    mayAskMaurice: Boolean,
    streaming: Boolean,
    canDictate: Boolean,
    transcribe: suspend (Recording) -> String?,
    readArticle: suspend (String, String?) -> Snippet.Article,
    room: Boolean,
    accent: Color,
    onPost: (String, List<String>) -> Unit,
    onSend: (String, List<String>) -> Unit,
    onStop: () -> Unit,
    /** The model of the conversation's Maurice, switchable. */
    modelPill: @Composable () -> Unit = {},
    onAddContext: (() -> Unit)? = null,
    onOpenTools: (() -> Unit)? = null,
    /** + → Files: a document from the device, filed in the library and added to the context. */
    onAddFile: (() -> Unit)? = null,
    hasContext: Boolean = false,
    /** The entry a new conversation is held from, before its first message. */
    heldEntry: ConversationEntry? = null,
    /** Whether its fiche is still in the tray: the member may have taken it out. */
    heldInContext: Boolean = false,
    onReleaseEntry: () -> Unit = {},
    trayOpen: Boolean = false,
    onToggleTray: () -> Unit = {},
    onSent: () -> Unit = {},
    /** The context tray, shown above the field when open. */
    tray: @Composable () -> Unit = {},
) {
    val theme = LocalMauriceTheme.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    // Photos ready to go: already upright, shrunk and encoded as JPEG.
    var photos by remember { mutableStateOf(listOf<ByteArray>()) }
    // Long pastes and shared articles, carried as cards above the field.
    var snippets by remember { mutableStateOf(listOf<Snippet>()) }
    var readingArticle by remember { mutableStateOf<String?>(null) }

    // A share from another app lands here: its page read as an article, its
    // text in the field (or as a card if long), its photos attached.
    val shared by ShareInbox.pending.collectAsState()
    LaunchedEffect(shared) {
        val draft = ShareInbox.take() ?: return@LaunchedEffect
        // Taking the draft changes `shared`, which restarts this effect: the
        // work goes on in the composer's own scope, or it would be cancelled.
        scope.launch {
            draft.text?.let { if (Snippet.isLong(it)) snippets = snippets + Snippet.Paste(it) else text = it }
            if (draft.images.isNotEmpty()) {
                val ready = withContext(Dispatchers.Default) { draft.images.mapNotNull(::normalizeToJpeg) }
                photos = (photos + ready).take(PhotoSize.MAX_PHOTOS)
            }
            draft.url?.let { url ->
                readingArticle = draft.title ?: url
                snippets = snippets + readArticle(url, draft.title)
                readingArticle = null
            }
        }
    }
    val pick =rememberPhotoPicker(PhotoSize.MAX_PHOTOS - photos.size) { picked ->
        scope.launch {
            val ready = withContext(Dispatchers.Default) { picked.mapNotNull(::normalizeToJpeg) }
            photos = (photos + ready).take(PhotoSize.MAX_PHOTOS)
        }
    }
    // The system camera, for one photo: upright and shrunk as a picked one is.
    val camera = rememberCamera { shot ->
        scope.launch {
            val ready = withContext(Dispatchers.Default) { normalizeToJpeg(shot) } ?: return@launch
            photos = (photos + ready).take(PhotoSize.MAX_PHOTOS)
        }
    }
    // + → Paste Image: the clipboard's image beside the pending photos, through
    // the same normalisation as the picker and the camera.
    val imageClipboard = rememberImageClipboard()
    var clipboardHasImage by remember { mutableStateOf(false) }
    fun pasteImage() {
        scope.launch {
            val bytes = imageClipboard.read() ?: return@launch
            val ready = withContext(Dispatchers.Default) { normalizeToJpeg(bytes) } ?: return@launch
            photos = (photos + ready).take(PhotoSize.MAX_PHOTOS)
        }
    }
    // Dictation: the recording, then the household's Whisper; the words join the field.
    val recorder = rememberRecorder()
    var recording by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    // Send pressed while the words are on their way: send once they are in.
    var sendAfterTranscript by remember { mutableStateOf<((String, List<String>) -> Unit)?>(null) }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    val canSend = text.isNotBlank() || photos.isNotEmpty() || snippets.isNotEmpty()
    fun submit(to: (String, List<String>) -> Unit) {
        if (transcribing) { sendAfterTranscript = to; return }
        if (!canSend || readingArticle != null) return
        to(Snippet.compose(text.trim(), snippets), photos.map(PhotoSize::dataUri))
        onSent()
        text = ""
        photos = emptyList()
        snippets = emptyList()
    }
    fun send() { if (!streaming && mayAskMaurice) submit(onSend) }
    fun post() = submit(onPost)

    fun stopDictation() {
        val clip = recorder.stop()
        recording = false
        if (clip == null) return
        transcribing = true
        scope.launch {
            val words = transcribe(clip)
            if (!words.isNullOrBlank()) text = if (text.isBlank()) words else text.trimEnd() + " " + words
            transcribing = false
            sendAfterTranscript?.let { to -> sendAfterTranscript = null; submit(to) }
        }
    }
    val askMic = rememberMicPermission { granted ->
        if (granted && recorder.start()) recording = true
    }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        tray()
        // One bubble, as on iOS: what rides along, the field, and the actions
        // under it — the field has the bubble's whole width.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(theme.surface)
                .border(0.5.dp, theme.rule, RoundedCornerShape(22.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (snippets.isNotEmpty() || readingArticle != null || heldEntry != null) {
                Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // It is in the context; a tap shows the tray that holds it.
                    heldEntry?.let { entry ->
                        val caption = listOfNotNull(
                            L("suggest.kind.${entry.collection}"),
                            L("chat.entry.in_context").takeIf { heldInContext },
                        ).joinToString(" · ")
                        SnippetCard(entry.title, caption, onRemove = onReleaseEntry, onOpen = onToggleTray, background = theme.bg)
                    }
                    readingArticle?.let { SnippetCard(it, L("article.reading"), onRemove = null, background = theme.bg) }
                    snippets.forEachIndexed { i, snippet ->
                        val (title, caption) = when (snippet) {
                            is Snippet.Paste -> L("pasted.title") to if (snippet.lines == 1) L("pasted.one_line") else L("pasted.lines", snippet.lines)
                            is Snippet.Article -> snippet.title.ifEmpty { snippet.url } to listOf(
                                snippet.source,
                                if (snippet.wordCount == 0) L("article.link_only") else L("article.title") + " · " + L("article.words", snippet.wordCount),
                            ).filter { it.isNotEmpty() }.joinToString(" · ")
                        }
                        SnippetCard(title, caption, onRemove = { snippets = snippets.filterIndexed { j, _ -> j != i } }, background = theme.bg)
                    }
                }
            }
            if (photos.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    itemsIndexed(photos) { index, photo ->
                        PhotoThumb(photo) { photos = photos.filterIndexed { i, _ -> i != index } }
                    }
                }
            }
            // About two lines tall, growing to ten before it scrolls.
            val style = TextStyle(fontSize = 17.sp, color = theme.ink)
            // The field's own value, for its caret and an input method's
            // composition (Enter must not send while one is under way);
            // `text` stays the draft, and the field follows it when it is set
            // from elsewhere — a share, dictation, a send.
            val shown = if (field.text == text) field else TextFieldValue(text, TextRange(text.length))
            BasicTextField(
                value = shown,
                onValueChange = { new ->
                    // A long paste leaves the field for a card, as on iOS.
                    val paste = Snippet.insertion(text, new.text)
                    if (paste != null) {
                        snippets = snippets + Snippet.Paste(paste.first)
                        text = paste.second
                        field = TextFieldValue(paste.second, TextRange(paste.second.length))
                    } else {
                        text = new.text
                        field = new
                    }
                },
                textStyle = style,
                cursorBrush = SolidColor(theme.ink),
                minLines = 2,
                maxLines = 10,
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                        if (text.isEmpty()) Text(L("chat.composer.placeholder"), style = style.copy(color = theme.inkMute))
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                    // Enter sends from a hardware keyboard, Shift-Enter breaks
                    // the line; an on-screen keyboard's Enter still breaks it
                    // (ComposerEnter.kt, maurice-android#3).
                    if (event.type != KeyEventType.KeyDown || (event.key != Key.Enter && event.key != Key.NumPadEnter)) return@onPreviewKeyEvent false
                    val action = enterAction(
                        hardware = event.isFromHardwareKeyboard(),
                        composing = shown.composition != null,
                        ctrl = event.isCtrlPressed,
                        shift = event.isShiftPressed,
                        alt = event.isAltPressed,
                        room = room,
                        // Words still being transcribed count: the send waits for them.
                        canSend = canSend || transcribing,
                        canAskMaurice = !streaming && mayAskMaurice,
                    )
                    when (action) {
                        EnterAction.Pass -> return@onPreviewKeyEvent false
                        EnterAction.Swallow -> {}
                        EnterAction.NewLine -> {
                            val s = shown.selection
                            val broken = shown.text.replaceRange(s.min, s.max, "\n")
                            text = broken
                            field = TextFieldValue(broken, TextRange(s.min + 1))
                        }
                        EnterAction.AskMaurice -> send()
                        EnterAction.PostToRoom -> post()
                    }
                    true
                },
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // One + for everything that adds to the turn: a photo, context, the conversation's tools.
                Box {
                    var menu by remember { mutableStateOf(false) }
                    // Whether there is an image to paste is read as the menu opens.
                    IconButton(onClick = { clipboardHasImage = imageClipboard.hasImage(); menu = true }, enabled = !recording && !streaming, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Add, L("composer.add"), tint = theme.inkSoft)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (camera != null) DropdownMenuItem(
                            text = { Text(L("composer.camera")) }, enabled = photos.size < PhotoSize.MAX_PHOTOS,
                            leadingIcon = { Icon(DomainGlyphs.of("camera"), null, Modifier.size(18.dp)) }, onClick = { menu = false; camera() },
                        )
                        DropdownMenuItem(
                            text = { Text(L("composer.photo")) }, enabled = photos.size < PhotoSize.MAX_PHOTOS,
                            leadingIcon = { Icon(MauriceIcons.Photo, null, Modifier.size(18.dp)) }, onClick = { menu = false; pick() },
                        )
                        if (onAddFile != null) DropdownMenuItem(
                            text = { Text(L("composer.file")) },
                            leadingIcon = { Icon(DomainGlyphs.of("folder"), null, Modifier.size(18.dp)) }, onClick = { menu = false; onAddFile() },
                        )
                        // Only while the clipboard holds an image: never a paste that would do nothing.
                        if (clipboardHasImage && photos.size < PhotoSize.MAX_PHOTOS) DropdownMenuItem(
                            text = { Text(L("composer.paste_image")) },
                            leadingIcon = { Icon(MauriceIcons.ContentPaste, null, Modifier.size(18.dp)) }, onClick = { menu = false; pasteImage() },
                        )
                        if (onAddContext != null) DropdownMenuItem(
                            text = { Text(L("chat.add_context")) },
                            leadingIcon = { Icon(DomainGlyphs.of("text.badge.plus"), null, Modifier.size(18.dp)) }, onClick = { menu = false; onAddContext() },
                        )
                        if (onOpenTools != null) DropdownMenuItem(
                            text = { Text(L("chat.tools_title")) },
                            leadingIcon = { Icon(DomainGlyphs.of("wrench.adjustable"), null, Modifier.size(18.dp)) }, onClick = { menu = false; onOpenTools() },
                        )
                    }
                }
                // Once context is attached, the suitcase shows it and opens the tray.
                if (hasContext) IconButton(onClick = onToggleTray, modifier = Modifier.size(40.dp)) {
                    Icon(DomainGlyphs.of("suitcase"), L("chat.review_context"), tint = if (trayOpen) legible(accent, theme.isDark) else theme.inkSoft, modifier = Modifier.size(20.dp))
                }
                modelPill()
                // While listening, the gap up to the mic carries what it hears.
                if (recording) LevelMeter(recorder, legible(accent, theme.isDark), Modifier.weight(1f).height(32.dp).padding(horizontal = 6.dp))
                else Spacer(Modifier.weight(1f))
                if (canDictate || recording) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).clickable(enabled = !transcribing) { if (recording) stopDictation() else askMic() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (transcribing) CircularProgressIndicator(Modifier.size(18.dp), color = theme.inkSoft, strokeWidth = 2.dp)
                        else Icon(MauriceIcons.Mic, L(if (recording) "chat.dictate_stop" else "chat.dictate"), tint = if (recording) legible(accent, theme.isDark) else theme.inkSoft)
                    }
                }
                // ➤ on the member's colour, muted while there is nothing to send; ⏹ while Maurice answers.
                val enabled = streaming || (canSend && mayAskMaurice)
                Box(
                    Modifier.size(40.dp).alpha(if (enabled) 1f else 0.35f).clip(CircleShape).background(accent)
                        .clickable(enabled = enabled) { if (streaming) onStop() else send() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (streaming) Box(Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
                    else Icon(DomainGlyphs.of("arrow.up"), L("chat.send"), tint = Color.White, modifier = Modifier.size(20.dp))
                }
                if (room) {
                    // 💬 — the room's default: filled in the member's colour once there is a draft.
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .background(if (canSend) accent else Color.Transparent)
                            .clickable(enabled = canSend, onClick = ::post),
                        contentAlignment = Alignment.Center,
                    ) { Icon(MauriceIcons.ChatBubble, L("chat.post_humans"), tint = if (canSend) Color.White else theme.inkMute, modifier = Modifier.size(19.dp)) }
                }
            }
        }
    }
}

/** A member's colour, lifted on dark grounds so a name stays readable. */
internal fun legible(color: Color, onDark: Boolean): Color =
    if (onDark && color.luminance() < 0.25f) lerp(color, Color.White, 0.45f) else color

/**
 * The voice's loudness as bars running right to left, newest by the mic —
 * `DictationWaveform` — with the time recorded.
 */
@Composable
private fun LevelMeter(recorder: Recorder, color: Color, modifier: Modifier) {
    val theme = LocalMauriceTheme.current
    var levels by remember { mutableStateOf(List(48) { 0f }) }
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(recorder) {
        var ticks = 0
        while (true) {
            delay(50)
            levels = levels.drop(1) + recorder.level().coerceIn(0f, 1f)
            if (++ticks % 20 == 0) seconds++
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.weight(1f).height(28.dp)) {
            val step = size.width / levels.size
            levels.forEachIndexed { i, level ->
                val h = maxOf(3f, level * size.height)
                drawRoundRect(
                    color.copy(alpha = 0.4f + 0.5f * i / levels.size),
                    topLeft = androidx.compose.ui.geometry.Offset(i * step + step * 0.2f, (size.height - h) / 2),
                    size = androidx.compose.ui.geometry.Size(step * 0.6f, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(step * 0.3f),
                )
            }
        }
        Spacer(Modifier.size(10.dp))
        Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", fontSize = 13.sp, color = theme.inkSoft, fontFamily = FontFamily.Monospace)
    }
}

/** A paste or an article riding above the field: what it is, with its ✕. */
@Composable
private fun SnippetCard(title: String, caption: String, onRemove: (() -> Unit)?, loading: Boolean = onRemove == null, onOpen: (() -> Unit)? = null, background: Color = LocalMauriceTheme.current.surface) {
    val theme = LocalMauriceTheme.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(background)
            .clickable(enabled = onOpen != null) { onOpen?.invoke() }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = theme.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(caption, fontSize = 11.sp, color = theme.inkMute, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = theme.inkMute)
        else if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Close, null, tint = theme.inkMute, modifier = Modifier.size(14.dp)) }
    }
}

/** An attached photo, with its ✕. */
@Composable
private fun PhotoThumb(jpeg: ByteArray, onRemove: () -> Unit) {
    val theme = LocalMauriceTheme.current
    val bitmap = remember(jpeg) { runCatching { jpeg.decodeToImageBitmap() }.getOrNull() }
    Box(Modifier.size(64.dp)) {
        if (bitmap != null) {
            Image(bitmap, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
        } else {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(theme.rule))
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(CircleShape)
                .background(theme.ink.copy(alpha = 0.7f)).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Close, null, tint = theme.bg, modifier = Modifier.size(12.dp)) }
    }
}
