package eu.chezmaurice.maurice.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import eu.chezmaurice.core.model.Message
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Air above a pinned question. */
internal val PIN_AIR = 8.dp
/** The thread's padding above its first message and under the end of its text. */
internal val THREAD_PADDING = 16.dp

/** The last item: the end of the text, then the room kept under a pinned turn. */
private const val END = "thread-end"

/**
 * The scrolling conversation: [content] (the messages, then the reply being
 * written), the end of the text, and the "↓" that goes there. Where it
 * scrolls is [scroll]'s decision; this does the scrolling and tells it what
 * the layout and the member's hand did.
 *
 * Laid out from the top down: the first line on screen stays where it is as
 * the content under it grows, which is what holds a pinned question still.
 * Following the end is done here, on each layout, while [scroll] says so.
 */
@Composable
internal fun ChatThread(
    scroll: ThreadScroll,
    conversation: String?,
    messages: List<Message>,
    memberId: String,
    /** A turn is running or its reply still typing out. */
    turnLive: Boolean,
    /** Rows [content] adds after the messages (the reply being written). */
    rowsAfter: Int,
    content: LazyListScope.() -> Unit,
) {
    val theme = LocalMauriceTheme.current
    val density = LocalDensity.current
    // Mounted on its last item: a thread opens on its end, with no frame of its top.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = messages.size + rowsAfter)
    val scope = rememberCoroutineScope()
    val currentConversation by rememberUpdatedState(conversation)
    val currentMessages by rememberUpdatedState(messages)
    var tail by remember { mutableFloatStateOf(0f) }
    var showDown by remember { mutableStateOf(false) }
    // The scroll this view is making (a pin, a move to the end): the
    // follow leaves it alone, the member's hand cancels it.
    var moving by remember { mutableStateOf<Job?>(null) }

    fun lastIsOwn(): Boolean = currentMessages.lastOrNull()?.let { it.role == "user" && it.authorId == memberId } == true

    /** The pinned question's row: the member's last message, found by position (its local id gives way to the server's). */
    fun pinnedIndex(): Int? =
        if (!scroll.pinnedIn(currentConversation)) null
        else currentMessages.indexOfLast { it.role == "user" && it.authorId == memberId }.takeIf { it >= 0 }

    fun measure(info: LazyListLayoutInfo) {
        val viewport = info.viewportSize.height.toFloat()
        if (viewport <= 0f) return
        val top = info.viewportStartOffset.toFloat()
        val bottom = info.viewportEndOffset.toFloat()
        val end = info.visibleItemsInfo.lastOrNull { it.key == END }
        pinnedIndex()?.let { index ->
            val question = info.visibleItemsInfo.firstOrNull { it.index == index }
            val pin = question?.let { scroll.pinLine(it.offset.toFloat(), it.size.toFloat(), viewport) }
            scroll.measureTurn(pin, end?.offset?.toFloat(), top, bottom)
        }
        tail = scroll.tail(currentConversation, viewport)
        scroll.bottomSeen(end != null && end.offset < bottom, viewport)
        showDown = scroll.showsScrollToEnd
    }

    /** Puts the end of the text (not of the room under it) at the bottom of the viewport. */
    suspend fun LazyListState.toTextEnd(animated: Boolean) {
        if (layoutInfo.totalItemsCount == 0) return
        if (layoutInfo.visibleItemsInfo.none { it.key == END }) scrollToItem(layoutInfo.totalItemsCount - 1)
        val info = layoutInfo
        val end = info.visibleItemsInfo.lastOrNull { it.key == END } ?: return
        val delta = (end.offset - (info.viewportEndOffset - info.afterContentPadding)).toFloat()
        if (abs(delta) < 1f) return
        if (animated) animateScrollBy(delta, tween(200, easing = FastOutSlowInEasing)) else scrollBy(delta)
    }

    /** How far the pinned question's line is from the top of the viewport, when it is laid out. */
    fun pinDelta(index: Int): Float? {
        val info = listState.layoutInfo
        val question = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
        val viewport = info.viewportSize.height.toFloat()
        return scroll.pinLine(question.offset.toFloat(), question.size.toFloat(), viewport) - info.viewportStartOffset
    }

    suspend fun frames(n: Int) = repeat(n) { withFrameNanos { } }

    /**
     * The question just sent goes to the top of the viewport and stays: once
     * it and the room under the turn are laid out, then again a moment later
     * (a keyboard or a card may have resized the thread under the first move)
     * unless the member has taken the thread meanwhile.
     */
    suspend fun pinQuestion() {
        frames(2)
        val index = pinnedIndex() ?: return
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
        measure(listState.layoutInfo)
        frames(2)
        if (!scroll.holding) return
        pinDelta(index)?.let { listState.animateScrollBy(it, tween(250, easing = FastOutSlowInEasing)) }
        delay(400)
        if (!scroll.holding || scroll.scrolledSincePin) return
        pinDelta(index)?.takeIf { abs(it) > 0.5f }?.let { listState.scrollBy(it) }
    }

    fun perform(move: ThreadScroll.Move) {
        if (move == ThreadScroll.Move.None) return
        moving?.cancel()
        moving = scope.launch {
            when (move) {
                ThreadScroll.Move.Pin -> pinQuestion()
                is ThreadScroll.Move.ToEnd -> { frames(1); listState.toTextEnd(move.animated) }
                ThreadScroll.Move.None -> {}
            }
        }
    }

    // The member's hand — a finger, a fling, a wheel — goes through nested
    // scrolling; this view's own scrolls do not. That is what tells a scroll
    // they made from the layout moving the end of the text.
    val hand = remember(scroll, listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!scroll.hand) {
                    moving?.cancel()
                    scroll.handChanged(true, listState.layoutInfo.viewportSize.height.toFloat())
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Content moved down: the member went up, toward earlier messages.
                if (consumed.y > 0.5f) scroll.scrolledUp()
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
            if (!inProgress && scroll.hand) {
                // The scroll they started has come to rest.
                scroll.handChanged(false, listState.layoutInfo.viewportSize.height.toFloat())
                measure(listState.layoutInfo)
            }
        }
    }

    // Each layout: what it shows of the pinned turn, whether the end is in
    // view, and — while following — the end kept at the bottom as the reply
    // grows, a card lands or the keyboard opens. The follow runs beside the
    // measuring, never inside it: a scroll waits for the member's own to
    // finish, and the layout must go on being read.
    var followJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }.collect { info ->
            measure(info)
            if (!scroll.follows || moving?.isActive == true || followJob?.isActive == true || listState.isScrollInProgress) return@collect
            val end = info.visibleItemsInfo.lastOrNull { it.key == END }
            if (end == null || end.offset > info.viewportEndOffset - info.afterContentPadding + 1) {
                // From the next frame: never in the middle of the layout that asked for it.
                followJob = scope.launch { withFrameNanos { }; if (scroll.follows) listState.toTextEnd(animated = false) }
            }
        }
    }

    // The thread appears: to its end, or a new conversation's one question pinned.
    LaunchedEffect(Unit) { perform(scroll.mounted(conversation, messages.size, lastIsOwn())) }
    // A message more or fewer.
    val seenCount = remember { intArrayOf(messages.size) }
    LaunchedEffect(messages.size) {
        val old = seenCount[0]
        if (old == messages.size) return@LaunchedEffect
        seenCount[0] = messages.size
        perform(scroll.messagesChanged(conversation, old, messages.size, lastIsOwn()))
    }
    // The reply is done: to the end for a reader who was following, no jump for another.
    val wasLive = remember { booleanArrayOf(turnLive) }
    LaunchedEffect(turnLive) {
        if (wasLive[0] && !turnLive) perform(scroll.turnEnded())
        wasLive[0] = turnLive
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(hand),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = THREAD_PADDING),
        ) {
            content()
            item(key = END) {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(1.dp))
                    if (tail > 0f) Spacer(Modifier.height(with(density) { tail.toDp() }))
                }
            }
        }

        // There is text under the fold: one tap goes to its end and follows from there.
        AnimatedVisibility(
            visible = showDown,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.8f),
            exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.8f),
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(theme.surface)
                    .border(0.5.dp, theme.rule, CircleShape)
                    .clickable { perform(scroll.scrollToEndTapped()) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    DomainGlyphs.of("arrow.up"), L("chat.scroll_to_end"), tint = theme.inkSoft,
                    modifier = Modifier.size(16.dp).rotate(180f),
                )
            }
        }
    }
}
