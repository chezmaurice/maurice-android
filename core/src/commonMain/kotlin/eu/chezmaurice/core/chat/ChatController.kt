package eu.chezmaurice.core.chat

import eu.chezmaurice.core.model.Conversation
import eu.chezmaurice.core.model.DataBlock
import eu.chezmaurice.core.model.Message
import eu.chezmaurice.core.model.Participant
import eu.chezmaurice.core.model.SearchHit
import eu.chezmaurice.core.model.TurnUsage
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** One tool in the activity line, folded by name. */
data class ToolStep(val tool: String, val count: Int, val running: Boolean)

/** The reply being written. */
data class Streaming(
    val text: String = "",
    val data: List<DataBlock> = emptyList(),
    val usage: TurnUsage? = null,
    val steps: List<ToolStep> = emptyList(),
    val thinking: Boolean = false,
    val imagePrompt: String? = null,
    /** The connection dropped; Maurice goes on and the app re-attaches. */
    val reconnecting: Boolean = false,
    /**
     * The turn is over and stored under this id; the row is typing out what
     * is left before the message takes its place (see [ChatController.revealed]).
     */
    val finishedId: String? = null,
)

data class ChatState(
    val conversations: List<Conversation> = emptyList(),
    val hasMore: Boolean = false,
    val loadingList: Boolean = false,
    val activeId: String? = null,
    val messages: List<Message> = emptyList(),
    val participants: List<Participant> = emptyList(),
    val loadingMessages: Boolean = false,
    /**
     * A turn is running: the composer offers ⏹. What it writes is not here
     * but in [ChatController.live], so that only the row showing it redraws
     * with each token.
     */
    val turnRunning: Boolean = false,
    /** What the server said went wrong, shown as a banner. */
    val error: String? = null,
    /** The search typed in the list (two characters or more), and what it found. */
    /**
     * The tools each reply of this session used, as they were watched — the
     * server keeps only the blocks. Session-only, as on iOS.
     */
    val trails: Map<String, List<ToolStep>> = emptyMap(),
    /** Someone else summoned Maurice in this room; his reply has not come yet. */
    val othersSummoned: Boolean = false,
    /**
     * The domain or companion a blank page is armed with ("Talk about it"):
     * the conversation is created bound to it on the first send.
     */
    val armedMauriceId: String? = null,
    val searchQuery: String = "",
    val searchResults: List<SearchHit>? = null,
    val searching: Boolean = false,
    /** The garden entry the conversation on screen is held from (asked about from Carnet), if any. */
    val activeEntry: eu.chezmaurice.core.model.ConversationEntry? = null,
    /**
     * Conversations read as read-only by their detail, with the household they
     * came from ("" when the server did not name it).
     */
    val receivedFrom: Map<String, String> = emptyMap(),
) {
    val active: Conversation? get() = conversations.firstOrNull { it.id == activeId }

    /**
     * The open conversation, when it came from another household with an
     * entry given here (specs/cross-household-share.md, E): nothing can be
     * written in it. Its household's name, "" when unnamed; null for an
     * ordinary conversation.
     */
    val activeReceivedFrom: String? get() {
        val id = activeId ?: return null
        receivedFrom[id]?.let { return it }
        return active?.takeIf { it.readOnly == true }?.let { it.importedFrom ?: "" }
    }

    /** Who answers here: the open conversation's domain, or the one the blank page is armed with. */
    val mauriceId: String? get() = if (activeId == null) armedMauriceId else active?.mauriceId
}

/** Why a banner shows, when the app (not the server) has the words. */
object ChatErrors {
    const val BUSY = "chat.error.busy"
    const val LOST = "chat.error.lost"
    const val NETWORK = "chat.notice.network"
    /** Not JSON (a proxy's error page) or not the shape expected (ChatService.report, DecodingError). */
    const val UNREADABLE = "chat.error.unreadable"
    /** The household has no such route yet: older than this app (`unknown_route`, or the old site engine's 403). */
    const val HOUSEHOLD_OLDER = "chat.error.household_older"
}

/**
 * The conversations of the active member and the turn under way — the
 * Kotlin side of `ChatService.swift`. One per signed-in member.
 */
class ChatController(
    private val api: ChatApi,
    private val scope: CoroutineScope,
    private val memberId: String,
    /** How long a finished reply may take to be typed out before the message replaces it anyway. */
    private val revealTimeoutMillis: Long = 10_000,
    /** For the live sockets; null in tests and previews, which have none. */
    private val client: ApiClient? = null,
) {
    /**
     * Called with a conversation created from a blank page, before its first
     * turn: the context gathered on the page is saved there first.
     */
    var onCreated: (suspend (String) -> Unit)? = null

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _live = MutableStateFlow<Streaming?>(null)
    /** The reply being written, token by token; null between turns. */
    val live: StateFlow<Streaming?> = _live.asStateFlow()

    /** Whether there is a reply row at all — changes twice a turn, not per token. */
    val hasLive: StateFlow<Boolean> = live.map { it != null }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    private var revealSignal: CompletableDeferred<Unit>? = null

    // ── Live sockets ───────────────────────────────────────

    private var userSocket: LiveSocket<UserEvent>? = null
    private var roomSocket: LiveSocket<RoomEvent>? = null
    private var roomId: String? = null
    private var socketsOn = false
    /** The sockets were closed once (the app went to the background). */
    private var wasPaused = false

    /** Open the member's socket and the one of the conversation on screen (foreground). */
    fun resumeLive() {
        val client = client ?: return
        socketsOn = true
        val user = userSocket ?: LiveSocket(client, "/api/me/ws", ::parseUserEvent, ::onUserEvent, onReconnected = {
            scope.launch { refreshList() }
        }).also { userSocket = it }
        user.start(scope)
        _state.value.activeId?.let { watchRoom(it) }
        // Back from the background: the bus does not replay what was said
        // meanwhile — a room someone added us to, messages in the open one.
        if (wasPaused) {
            wasPaused = false
            scope.launch {
                refreshList()
                _state.value.activeId?.let { id -> report { reload(id) } }
            }
        }
    }

    /** Close them (background): the server pushes a notification instead. */
    fun pauseLive() {
        if (socketsOn) wasPaused = true
        socketsOn = false
        userSocket?.stop()
        roomSocket?.stop()
        roomSocket = null
        roomId = null
    }

    private fun watchRoom(id: String?) {
        if (roomId == id) return
        roomSocket?.stop()
        roomSocket = null
        roomId = id
        val client = client ?: return
        if (id == null || !socketsOn) return
        roomSocket = LiveSocket(client, "/api/conversations/$id/ws", ::parseRoomEvent, { onRoomEvent(id, it) }, onReconnected = {
            scope.launch { report { reload(id) } }
        }, onShut = {
            // Taken out of the room, or left it from another device: the server
            // closed the socket for good. It goes from the list, as on leaving.
            if (roomId == id) { roomSocket = null; roomId = null; dropRoom(id) }
        }).also { it.start(scope) }
    }

    /** What Carnet could keep from a conversation (`/api/suggestions`); null without a server (tests). */
    val suggestions: eu.chezmaurice.core.carnet.SuggestionsApi? = client?.let { eu.chezmaurice.core.carnet.SuggestionsApi(it) }

    /** Kept a row linked to an entry: the conversation may be held from it now. */
    suspend fun refreshActiveEntry() {
        val id = _state.value.activeId ?: return
        runCatching { reload(id) }
    }

    /**
     * Let go of the entry a new conversation is held from — the × on its
     * tile in the composer (`ChatService.releaseEntry`). True when it is
     * done, so the tray can be read again.
     */
    suspend fun releaseEntry(): Boolean {
        val s = _state.value
        val id = s.activeId ?: return false
        if (s.activeEntry == null) return false
        return try {
            api.releaseEntry(id)
            if (_state.value.activeId != id) return false
            _state.update { st ->
                st.copy(activeEntry = null, conversations = st.conversations.map { if (it.id == id) it.copy(entryRef = null) else it })
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            // A household older than this app has no such route (its site
            // engine answers 403, a newer server 404). The entry is still in
            // the context there, so the tile stays — said in plain words.
            if (e.status == 403 || e.status == 404) _state.update { it.copy(error = ChatErrors.HOUSEHOLD_OLDER) } else fail(e)
            false
        } catch (e: Throwable) {
            fail(e)
            false
        }
    }

    /** The drawer read the counts itself: the header's mark follows them at once. */
    fun setSuggestionCounts(conversationId: String, pending: Int, kept: Int) {
        _state.update { s -> s.copy(conversations = s.conversations.map { if (it.id == conversationId) it.copy(suggestions = pending, suggestionsKept = kept) else it }) }
    }

    /**
     * Ask the server what a conversation offers to keep, on opening it: the
     * header's mark must not depend on an event this device may never have
     * heard (`refreshSuggestionCount`). A count that cannot be read leaves the
     * list's.
     */
    private suspend fun refreshSuggestionCounts(id: String) {
        val counts = runCatching { suggestions?.list(id) }.getOrNull() ?: return
        setSuggestionCounts(id, counts.pending, counts.kept)
    }

    private fun onUserEvent(event: UserEvent) {
        val id = when (event) {
            is UserEvent.Added -> event.conversationId
            is UserEvent.Opened -> event.conversationId
            is UserEvent.Activity -> event.conversationId
            is UserEvent.Suggestions -> {
                _state.update { s -> s.copy(conversations = s.conversations.map { if (it.id == event.conversationId) it.copy(suggestions = event.count, suggestionsKept = event.kept) else it }) }
                return
            }
        }
        // Not the conversation on screen: it goes unread, and to the top.
        if (id != _state.value.activeId) {
            scope.launch {
                refreshList()
                _state.update { s -> s.copy(conversations = s.conversations.map { if (it.id == id) it.copy(unread = true) else it }) }
            }
        }
    }

    private fun onRoomEvent(id: String, event: RoomEvent) {
        if (_state.value.activeId != id) return
        when (event) {
            is RoomEvent.Participants -> _state.update { it.copy(participants = event.participants) }
            is RoomEvent.Summoned -> if (event.by != memberId) _state.update { it.copy(othersSummoned = true) }
            is RoomEvent.NewMessage -> {
                val m = event.message
                // Our own turn's reply comes on the socket before `done`: the
                // reply row is still typing it; finish() puts it in place.
                if (m.isAssistant && (_state.value.turnRunning || _live.value != null)) return
                _state.update { s ->
                    if (s.messages.any { it.id == m.id }) return@update s
                    val messages = if (m.authorId == memberId && !m.isAssistant) {
                        // Our own message, stored: it replaces its optimistic copy.
                        val local = s.messages.indexOfLast { it.id.startsWith("local-") && it.authorId == memberId }
                        if (local >= 0) s.messages.toMutableList().also { it[local] = m } else s.messages + m
                    } else s.messages + m
                    s.copy(messages = messages, othersSummoned = if (m.isAssistant) false else s.othersSummoned)
                }
            }
        }
    }

    private var turn: Job? = null
    /** The member pressed ⏹ during this turn: its closing error is no news. */
    private var stopRequested = false
    private val pageSize = 40

    // ── The list ───────────────────────────────────────────

    fun loadConversations() = scope.launch {
        _state.update { it.copy(loadingList = true) }
        report {
            val page = api.conversations(pageSize)
            _state.update { it.copy(conversations = page, hasMore = page.size >= pageSize) }
        }
        _state.update { it.copy(loadingList = false) }
    }

    fun loadMore() {
        val state = _state.value
        if (!state.hasMore || state.loadingList) return
        scope.launch {
            _state.update { it.copy(loadingList = true) }
            // A page that fails says nothing: hasMore stays, a later scroll tries again (as on iOS).
            runCatching { api.conversations(pageSize, before = state.conversations.lastOrNull()) }.onSuccess { page ->
                _state.update { s ->
                    val known = s.conversations.map { it.id }.toSet()
                    s.copy(conversations = s.conversations + page.filterNot { it.id in known }, hasMore = page.size >= pageSize)
                }
            }
            _state.update { it.copy(loadingList = false) }
        }
    }

    // ── Search ─────────────────────────────────────────────

    private var searchJob: Job? = null

    /**
     * Search every conversation (`GET /api/conversations/search`), from two
     * characters, a quarter second after the last keystroke. An answer to an
     * older query is dropped.
     */
    fun search(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        val q = query.trim()
        if (q.length < 2) {
            _state.update { it.copy(searchResults = null, searching = false) }
            return
        }
        searchJob = scope.launch {
            delay(250)
            _state.update { it.copy(searching = true) }
            // A search that fails shows no banner, as on iOS: the results stay as they were.
            runCatching { api.search(q) }.onSuccess { found ->
                if (found.q.trim() == _state.value.searchQuery.trim()) {
                    _state.update { it.copy(searchResults = found.results) }
                }
            }
            _state.update { it.copy(searching = false) }
        }
    }

    // ── One conversation ───────────────────────────────────

    fun open(id: String) {
        if (_state.value.activeId == id && _state.value.messages.isNotEmpty()) return
        turn?.cancel()
        clearTurn()
        _state.update { it.copy(activeId = id, armedMauriceId = null, messages = emptyList(), participants = emptyList(), error = null, othersSummoned = false, loadingMessages = true, activeEntry = null) }
        watchRoom(id)
        scope.launch {
            report { reload(id) }
            _state.update { it.copy(loadingMessages = false) }
            launch { refreshSuggestionCounts(id) }
            // Gone (404, the next one opened in its place) or left for another meanwhile.
            val elsewhere = _state.value.activeId != id
            // Opened from a notification: a room just joined is not in the list yet.
            if (!elsewhere && _state.value.conversations.none { it.id == id }) refreshList()
            runCatching { api.markRead(id) }
            if (elsewhere) return@launch
            _state.update { s -> s.copy(conversations = s.conversations.map { if (it.id == id) it.copy(unread = false) else it }) }
            // A reply may be under way from another device, or from before a restart.
            follow(id)
        }
    }

    /**
     * A blank page; the conversation is created on the first send, bound to
     * [mauriceId] (a domain or a companion) or to the everyday Maurice.
     */
    fun startNew(mauriceId: String? = null) {
        turn?.cancel()
        clearTurn()
        _state.update { it.copy(activeId = null, armedMauriceId = mauriceId, messages = emptyList(), participants = emptyList(), error = null, othersSummoned = false, activeEntry = null) }
        watchRoom(null)
    }

    /**
     * "New conversation from this one", under a conversation received
     * read-only: a new conversation held from the same entry, its fiche in
     * context (`POST /api/conversations {entry}`). Without an entry, a blank page.
     */
    fun startFromReceived() = scope.launch {
        val s = _state.value
        val entryRef = s.activeEntry?.ref ?: s.active?.entryRef
        if (entryRef == null) { startNew(); return@launch }
        report {
            val convo = api.createFromEntry(entryRef)
            startNew()
            _state.update { st -> if (st.conversations.any { it.id == convo.id }) st else st.copy(conversations = listOf(convo) + st.conversations) }
            open(convo.id)
        }
    }

    private fun clearTurn() {
        _live.value = null
        _state.update { it.copy(turnRunning = false) }
    }

    // ── The conversation's details ─────────────────────────

    /**
     * File a conversation (the one on screen by default) under one of the
     * member's domains, or take it out: the night never files it again.
     */
    suspend fun setDomain(mauriceId: String?, conversationId: String? = _state.value.activeId): Boolean {
        val id = conversationId ?: return false
        return try {
            api.setDomain(id, mauriceId)
            _state.update { s -> s.copy(conversations = s.conversations.map { if (it.id == id) it.copy(mauriceId = mauriceId, mauriceBoundBy = null) else it }) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(e); false
        }
    }

    /** Read the first page again — after an adoption bound conversations to a new domain. */
    fun refreshConversations() = scope.launch { refreshList() }

    /** Who may still be added to the room: the household, less who is in it. */
    suspend fun addable(): List<eu.chezmaurice.core.model.Member> {
        val inside = _state.value.participants.map { it.memberId }.toSet() + memberId
        return runCatching { api.roster() }.getOrDefault(emptyList()).filter { it.id !in inside }
    }

    fun addParticipant(otherId: String) {
        val id = _state.value.activeId ?: return
        scope.launch { report { val people = api.addParticipant(id, otherId); _state.update { it.copy(participants = people) } } }
    }

    /**
     * Take someone out of the room (added by mistake, say) — `removeParticipant`.
     * The room socket pushes the roster that holds; they leave the list at once.
     */
    fun removeParticipant(otherId: String) = scope.launch {
        val id = _state.value.activeId ?: return@launch
        report {
            api.removeParticipant(id, otherId)
            _state.update { s -> s.copy(participants = s.participants.filterNot { it.memberId == otherId }) }
        }
    }

    /** Leave a shared room: it goes from the list, and the next one opens. */
    fun leave() {
        val id = _state.value.activeId ?: return
        scope.launch {
            report {
                api.leave(id)
                dropRoom(id)
            }
        }
    }

    /** Report a message (or member) of the room on screen; true when the server took it. */
    suspend fun report(targetType: String, targetId: String, reason: String, note: String?): Boolean {
        val id = _state.value.activeId ?: return false
        return runCatching { api.report(id, targetType, targetId, reason, note) }.onFailure { fail(it) }.isSuccess
    }

    /** Block a member: the server stops showing them; the thread is read again without them. */
    suspend fun block(otherId: String): Boolean {
        val ok = runCatching { api.block(otherId) }.onFailure { fail(it) }.isSuccess
        if (ok) _state.value.activeId?.let { id -> report { reload(id) } }
        return ok
    }

    /**
     * A page shared from another app, as an article card: read by the server,
     * or just its title and address when it could not be read.
     */
    suspend fun readArticle(url: String, title: String?): Snippet.Article = try {
        // As readSharedArticle: the server's fields as it gives them (its date
        // is already YYYY-MM-DD, its address the one sent); the shared title
        // only when the server found none. No title at all: the card shows
        // the address, the message carries none.
        val a = api.readArticle(url)
        Snippet.Article(
            title = a.title?.takeIf { it.isNotEmpty() } ?: title.orEmpty(),
            site = a.siteName.orEmpty(), author = a.author.orEmpty(),
            date = a.publishedAt.orEmpty(), url = a.url.ifEmpty { url }, text = a.text.orEmpty(),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        Snippet.Article(title = title.orEmpty(), url = url)
    }

    // ── Dictation ──────────────────────────────────────────

    private val dictation = client?.let { eu.chezmaurice.core.dictation.DictationApi(it) }
    private val _canDictate = MutableStateFlow(false)
    /** Whether the household transcribes (`GET /api/dictation`). */
    val canDictate: StateFlow<Boolean> = _canDictate.asStateFlow()

    fun probeDictation() = scope.launch {
        _canDictate.value = dictation?.capability()?.available == true
    }

    /** The words heard, "" for silence or a clip too short; null after a failure (the banner says why). */
    suspend fun transcribe(recording: eu.chezmaurice.core.dictation.Recording): String? {
        val api = dictation ?: return null
        if (recording.seconds < eu.chezmaurice.core.dictation.DictationApi.MIN_SECONDS) return ""
        return try {
            api.transcribe(recording)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(e); null
        }
    }

    /** Keep or let go of a fact Maurice noted; answers its new state, or null if it failed. */
    suspend fun decideFact(id: String, keep: Boolean): String? =
        runCatching { api.decideFact(id, keep) }.getOrNull()

    /** Put the controller in a given state without a server — previews and screenshots only. */
    fun seed(state: ChatState, live: Streaming? = null) {
        _state.value = state
        _live.value = live
    }

    /** The reply row has typed everything out: the message may take its place. */
    fun revealed(messageId: String) {
        if (_live.value?.finishedId == messageId) revealSignal?.complete(Unit)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Only the title changes in the row: the rest (its domain, its counts) stays as the list had it. */
    fun rename(id: String, title: String) = scope.launch {
        report {
            val updated = api.rename(id, title)
            _state.update { s -> s.copy(conversations = s.conversations.map { if (it.id == id) it.copy(title = updated.title ?: title) else it }) }
        }
    }

    /**
     * Delete a conversation. A reply still running in it is stopped first;
     * it leaves the screen only once the server has taken the deletion.
     */
    fun delete(id: String) = scope.launch {
        if (_state.value.activeId == id) stopAndWait()
        report {
            api.delete(id)
            dropRoom(id)
        }
    }

    /**
     * Forget a room that is gone — deleted, left, or found missing (404). If
     * it was on screen, the first conversation left in the list opens in its
     * place (its thread read, not marked read), or a blank page when none is
     * left — ChatService.dropRoom.
     */
    private fun dropRoom(id: String) {
        _state.update { s -> s.copy(conversations = s.conversations.filterNot { it.id == id }) }
        if (_state.value.activeId != id) return
        val next = _state.value.conversations.firstOrNull()?.id
        if (next == null) { startNew(); return }
        turn?.cancel()
        clearTurn()
        _state.update { it.copy(activeId = next, messages = emptyList(), participants = emptyList(), othersSummoned = false, activeEntry = null) }
        watchRoom(next)
        scope.launch { report { reload(next) } }
    }

    private suspend fun reload(id: String) {
        try {
            val detail = api.conversation(id)
            if (_state.value.activeId != id) return
            _state.update { s ->
                s.copy(
                    messages = detail.messages.filter { m -> m.role != "system" }, participants = detail.participants, activeEntry = detail.entry,
                    conversations = s.conversations.map { if (it.id == id) it.copy(entryRef = detail.entry?.ref) else it },
                    receivedFrom = if (detail.readOnly == true) s.receivedFrom + (id to (detail.importedFrom ?: "")) else s.receivedFrom - id,
                )
            }
        } catch (e: ApiError) {
            // Deleted elsewhere: the next one opens.
            if (e.status == 404) dropRoom(id) else throw e
        }
    }

    // ── Turns ──────────────────────────────────────────────

    /**
     * Send what the member wrote. [mauriceId] is who answers (null = the
     * everyday Maurice); [images] are JPEG data URIs.
     */
    fun send(
        text: String,
        mauriceId: String? = _state.value.mauriceId,
        images: List<String> = emptyList(),
        /** False: a message to the people of a room, Maurice not called. */
        summon: Boolean = true,
    ) {
        if (text.isBlank() && images.isEmpty()) return
        if (summon && (_state.value.turnRunning || _live.value != null)) return
        val optimistic = Message(
            id = "local-" + Random.nextLong().toULong().toString(16),
            role = "user",
            content = (images.map { "![photo](pending)" } + text).joinToString("\n\n").trim(),
            authorId = memberId,
        )
        if (!summon) {
            // A word to the people of the room: no turn, and Maurice's reply,
            // if one is under way, goes on undisturbed.
            val id = _state.value.activeId ?: return
            _state.update { it.copy(messages = it.messages + optimistic, error = null) }
            scope.launch {
                try {
                    api.send(id, text, mauriceId, summon = false, images = images)
                    // The stored copy replaces the optimistic one over the room socket;
                    // without a socket, read the thread again.
                    if (roomSocket == null) report { reload(id) }
                } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                    _state.update { s -> s.copy(messages = s.messages.filterNot { it.id == optimistic.id }) }
                    fail(e)
                }
            }
            return
        }
        // A follower re-attached on opening (nothing running, most often) must
        // not outlive this send: two readers of one turn contradict each other.
        turn?.cancel()
        stopRequested = false
        _live.value = Streaming(thinking = true)
        _state.update { it.copy(messages = it.messages + optimistic, turnRunning = true, error = null) }
        turn = scope.launch {
            val id = try {
                _state.value.activeId ?: api.create(mauriceId).also { created ->
                    _state.update { s -> s.copy(activeId = created.id, armedMauriceId = null, conversations = listOf(created) + s.conversations) }
                    watchRoom(created.id)
                    onCreated?.invoke(created.id)
                }.id
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                // Not created: the message was never sent, it does not stay on the page.
                _state.update { s -> s.copy(messages = s.messages.filterNot { it.id == optimistic.id }) }
                fail(e); return@launch
            }
            runTurn(id) {
                when (val start = api.send(id, text, mauriceId, images = images)) {
                    is TurnStart.Streaming -> start.events
                    TurnStart.Posted -> null
                }
            }
        }
    }

    /** "Answer again": the server drops its last reply and writes a new one. */
    fun regenerate() {
        val state = _state.value
        val id = state.activeId ?: return
        if (state.turnRunning || _live.value != null) return
        // Nothing in the thread: nothing to answer again (ChatService.regenerate).
        val last = state.messages.lastOrNull() ?: return
        turn?.cancel()
        stopRequested = false
        _live.value = Streaming(thinking = true)
        _state.update {
            it.copy(
                messages = if (last.isAssistant) it.messages.dropLast(1) else it.messages,
                turnRunning = true, error = null,
            )
        }
        // The conversation's own domain — iOS sends null here, which re-binds
        // the conversation to the everyday Maurice.
        val mauriceId = state.active?.mauriceId
        turn = scope.launch {
            runTurn(id) {
                (api.send(id, "", mauriceId, regenerate = true) as? TurnStart.Streaming)?.events
            }
        }
    }

    /**
     * ⏹ — the server hears it first (a turn belongs to the conversation, not
     * to the request), then the follower is torn down: nothing re-attaches.
     * The partial reply, if any, comes over the room socket — read again
     * here when there is none.
     */
    fun stop() {
        val id = _state.value.activeId ?: return
        if (!_state.value.turnRunning) return
        stopRequested = true
        scope.launch { api.stop(id) }
        turn?.cancel()
        clearTurn()
        scope.launch {
            if (roomSocket == null) report { reload(id) }
            refreshList()
        }
    }

    /** Stop a running reply and wait until its follower is gone (before deleting its conversation). */
    private suspend fun stopAndWait() {
        val id = _state.value.activeId ?: return
        if (!_state.value.turnRunning) return
        stopRequested = true
        scope.launch { api.stop(id) }
        turn?.cancelAndJoin()
        clearTurn()
    }

    /** Re-attach to a reply running on the server, if any. */
    private suspend fun follow(id: String) {
        if (turn?.isActive == true) return
        stopRequested = false
        turn = scope.launch { runTurn(id, attachOnly = true) { api.follow(id) } }
    }

    /**
     * Read a turn to its end, re-attaching when the connection drops: the turn
     * belongs to the conversation, not to the request. Gives up after six
     * dropped streams or three failed attaches, as on iOS.
     */
    private suspend fun runTurn(id: String, attachOnly: Boolean = false, start: suspend () -> Flow<TurnEvent>?) {
        var drops = 0
        var failedAttaches = 0
        var source: (suspend () -> Flow<TurnEvent>?) = start
        var attaching = attachOnly
        while (true) {
            var terminal: TurnEvent? = null
            var sawAnything = false
            var resumedFinished = false
            try {
                val events = source()
                if (events == null) { finishPosted(id); return }
                events.collect { event ->
                    sawAnything = true
                    apply(event)
                    if (event is TurnEvent.Resume && event.finished) resumedFinished = true
                    if (event.isTerminal) terminal = event
                }
                failedAttaches = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (e.status == 409) {
                    // A reply is already running; the message is stored anyway.
                    _state.update { s -> s.copy(messages = s.messages.filterNot { it.id.startsWith("local-") }, error = ChatErrors.BUSY) }
                } else if (e.status != 0) {
                    fail(e); return
                } else if (attaching && ++failedAttaches >= 3) {
                    fail(ApiError(0, ChatErrors.LOST)); return
                }
            } catch (e: Throwable) {
                // The send itself never reached the server: say so, keep nothing.
                if (!attaching && !sawAnything) {
                    _state.update { s -> s.copy(messages = s.messages.filterNot { it.id.startsWith("local-") }) }
                    fail(e); return
                }
                if (attaching && ++failedAttaches >= 3) { fail(ApiError(0, ChatErrors.LOST)); return }
            }
            when (val end = terminal) {
                is TurnEvent.Done -> { finish(id, end.messageId ?: madeUpId()); return }
                // The end of a turn, never followed by a reconnect. A turn that
                // leaves nothing to store (no word from the model, or ⏹ before the
                // first) ends with an error too; after our own ⏹ it is not news.
                is TurnEvent.Error -> {
                    if (stopRequested) clearTurn() else fail(ApiError(0, end.message))
                    report { reload(id) }
                    refreshList()
                    return
                }
                else -> {}
            }
            if (resumedFinished) {
                // A server from before 21 Oct 2026 closes an empty turn with no
                // final event: a finished resume with nothing after it is the end.
                clearTurn()
                report { reload(id) }
                refreshList()
                return
            }
            if (attaching && !sawAnything) {
                // 204: nothing running. The reply may have been stored meanwhile.
                clearTurn()
                report { reload(id) }
                return
            }
            if (++drops >= 6) { fail(ApiError(0, ChatErrors.LOST)); return }
            _live.update { (it ?: Streaming()).copy(reconnecting = true) }
            delay(if (failedAttaches > 0) 2_000 else 1_000)
            source = { api.follow(id) }
            attaching = true
        }
    }

    private fun apply(event: TurnEvent) = _live.update { live ->
        val current = live ?: Streaming()
        val next = when (event) {
            is TurnEvent.TextDelta -> current.copy(text = current.text + event.text, thinking = false, reconnecting = false)
            TurnEvent.Thinking -> current.copy(thinking = true, reconnecting = false)
            TurnEvent.Ping -> current.copy(reconnecting = false)
            is TurnEvent.ToolCall -> current.copy(steps = step(current.steps, event), reconnecting = false)
            is TurnEvent.ToolData -> current.copy(data = current.data + event.block)
            is TurnEvent.ImageLoading -> current.copy(text = "", imagePrompt = event.prompt)
            is TurnEvent.Image -> current.copy(text = "![${event.prompt}](${event.url})", imagePrompt = null)
            is TurnEvent.Usage -> current.copy(usage = event.usage)
            is TurnEvent.Resume -> current.copy(
                text = event.text, data = event.data, usage = event.usage, reconnecting = false,
                steps = event.tool?.let { tool ->
                    if (current.steps.any { it.tool == tool }) current.steps.map { if (it.tool == tool) it.copy(running = true) else it }
                    else current.steps + ToolStep(tool, 1, true)
                } ?: current.steps,
            )
            is TurnEvent.Done, is TurnEvent.Error -> current
        }
        next
    }

    private fun step(steps: List<ToolStep>, call: TurnEvent.ToolCall): List<ToolStep> {
        val existing = steps.firstOrNull { it.tool == call.tool }
        return when {
            call.started && existing == null -> steps + ToolStep(call.tool, 1, true)
            call.started -> steps.map { if (it.tool == call.tool) it.copy(count = it.count + 1, running = true) else it }
            else -> steps.map { if (it.tool == call.tool) it.copy(running = false) else it }
        }
    }

    /**
     * The turn is stored. The row types out what is left (iOS: "let the
     * reveal finish, max 10 s"), then the message takes its place, then the
     * thread and the list are read again for the stored ids and the title.
     */
    private suspend fun finish(id: String, messageId: String) {
        _state.update { it.copy(turnRunning = false) }
        val signal = CompletableDeferred<Unit>().also { revealSignal = it }
        _live.update { it?.copy(finishedId = messageId, thinking = false, reconnecting = false) }
        if (revealTimeoutMillis > 0) withTimeoutOrNull(revealTimeoutMillis) { signal.await() }
        val streaming = _live.value
        _state.update { s ->
            val trails = if (streaming?.steps.isNullOrEmpty()) s.trails else s.trails + (messageId to streaming!!.steps.map { it.copy(running = false) })
            if (s.activeId != id || s.messages.any { it.id == messageId } || streaming == null) s.copy(trails = trails)
            else s.copy(
                trails = trails,
                messages = s.messages + Message(
                    id = messageId, conversationId = id, role = "assistant", content = streaming.text,
                    data = streaming.data.ifEmpty { null }, usage = streaming.usage,
                ),
            )
        }
        _live.value = null
        report { reload(id) }
        refreshList()
    }

    /** For a reply the server stored without saying under which id (ChatService: a UUID); the reload replaces it. */
    private fun madeUpId(): String = kotlin.random.Random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private suspend fun finishPosted(id: String) {
        clearTurn()
        report { reload(id) }
        refreshList()
    }

    private suspend fun refreshList() {
        runCatching { api.conversations(pageSize) }.onSuccess { page ->
            _state.update { s ->
                val ids = page.map { it.id }.toSet()
                s.copy(conversations = page + s.conversations.filterNot { it.id in ids })
            }
        }
    }

    private fun fail(error: Throwable) {
        // As ChatService.report: "[402] Spending limit reached." — the app's own words alone.
        val message = (error as? ApiError)?.takeIf { it.isUnknownRoute }?.let { ChatErrors.HOUSEHOLD_OLDER }
            ?: (error as? ApiError)?.takeIf { it.message.isNotBlank() }?.shown
            ?: if (error is kotlinx.serialization.SerializationException) ChatErrors.UNREADABLE else ChatErrors.NETWORK
        _live.value = null
        _state.update { it.copy(turnRunning = false, error = message) }
    }

    private suspend fun report(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(e)
        }
    }
}
