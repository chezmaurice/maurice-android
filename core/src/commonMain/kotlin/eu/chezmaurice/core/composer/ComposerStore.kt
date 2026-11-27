package eu.chezmaurice.core.composer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ComposerState(
    val items: List<TrayItem> = emptyList(),
    val total: Int = 0,
    val over: Boolean = false,
    val tier: String = "light",
    /**
     * The domain's baked-in context, loaded with every turn but not removable
     * here: its name, how many items, what they weigh.
     */
    val lockedName: String = "",
    val lockedCount: Int = 0,
    val lockedWeight: Int = 0,
) {
    /** What Maurice actually loads: the domain's bundle and the member's items. */
    val displayTotal: Int get() = total + lockedWeight

    /** Binary attachments: single img/pdf files and those a folder sweeps in. */
    val attachmentCount: Int get() = items.sumOf { if (it.type == ItemType.Folder) it.binaryCount else if (it.type == ItemType.File && it.na) 1 else 0 }

    val hasContext: Boolean get() = items.isNotEmpty() || lockedCount > 0

    fun has(entryId: String) = items.any { it.id == entryId }
}

/**
 * The context tray of a conversation — what Maurice reads with each of its
 * turns (`ComposerStore.swift`). The server resolves and weighs; the app
 * edits the list, has it weighed after each change, then saves it. Bound to
 * no conversation, it is a domain editor's baked-in context, saved with the
 * domain.
 */
class ComposerStore(private val api: ComposerApi, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(ComposerState())
    val state: StateFlow<ComposerState> = _state.asStateFlow()

    private var conversationId: String? = null
    /** One edit's weigh-and-save at a time, in order. */
    private val edits = Mutex()
    private val summaryWaits = mutableSetOf<String>()
    private val chapters = HashMap<String, List<BookChapter>>()

    /**
     * The conversation on screen changed. A blank page that gathered context
     * and has just become a conversation keeps it and saves it there;
     * otherwise the tray is that conversation's saved one.
     */
    fun bind(id: String?, lockedName: String = "", lockedCount: Int = 0, lockedWeight: Int = 0) {
        _state.update { it.copy(lockedName = lockedName, lockedCount = lockedCount, lockedWeight = lockedWeight) }
        // Already there: the blank page's tray was carried over before its first turn.
        if (id != null && id == conversationId) return
        val carried = conversationId == null && id != null && _state.value.items.isNotEmpty()
        conversationId = id
        if (carried) { edit { it }; return }
        _state.update { it.copy(items = emptyList(), total = 0, over = false, tier = "light") }
        if (id == null) return
        load(id)
    }

    /** Read the conversation's saved tray again: the server changed it (an entry let go). */
    fun reload() {
        conversationId?.let(::load)
    }

    private fun load(id: String) {
        scope.launch {
            edits.withLock {
                val items = quietly { api.spec(id) } ?: return@withLock
                if (conversationId != id) return@withLock
                _state.update { it.copy(items = items) }
                reweigh()
            }
        }
    }

    /** Save the tray to a conversation just created from a blank page, before its first turn. */
    suspend fun carryTo(id: String) {
        conversationId = id
        if (_state.value.items.isEmpty()) return
        edits.withLock { quietly { api.save(id, _state.value.items) } }
    }

    /** A domain's own context, for its editor. */
    fun seed(items: List<TrayItem>) {
        conversationId = null
        _state.value = ComposerState(items = items)
        edit { it }
    }

    /** Files a document into the library and answers it as a picker entry; set by the app. */
    var uploader: (suspend (eu.chezmaurice.core.settings.Upload) -> SearchEntry?)? = null

    /** A document from the device, filed in the library and added here in one gesture. */
    suspend fun addUploaded(file: eu.chezmaurice.core.settings.Upload): Boolean {
        val entry = quietly { uploader?.invoke(file) } ?: return false
        add(entry)
        return true
    }

    fun add(e: SearchEntry) {
        if (_state.value.has(e.id)) return
        edit { it + TrayItem.from(e) }
    }

    fun toggle(e: SearchEntry) = if (_state.value.has(e.id)) remove(e.id) else add(e)

    fun remove(id: String) = edit { list -> list.filterNot { it.id == id } }

    fun update(item: TrayItem) = edit { list -> list.map { if (it.id == item.id) item else it } }

    /** Change the list, then weigh it and save it. */
    private fun edit(change: (List<TrayItem>) -> List<TrayItem>) {
        _state.update { it.copy(items = change(it.items)) }
        scope.launch {
            edits.withLock {
                reweigh()
                conversationId?.let { id -> quietly { api.save(id, _state.value.items) } }
            }
        }
    }

    private suspend fun reweigh() {
        val items = _state.value.items
        if (items.isEmpty()) { _state.update { it.copy(total = 0, over = false, tier = "light") }; return }
        val w = quietly { api.weigh(items) } ?: return
        _state.update { s ->
            // The list moved on while it was being weighed: the next weighing will say.
            if (s.items.map { it.id } != items.map { it.id }) return@update s
            val weighed = s.items.mapIndexed { i, it -> w.items.getOrNull(i)?.let(it::weighed) ?: it }
            s.copy(items = weighed, total = w.total, over = w.over, tier = w.tier)
        }
        for (it in _state.value.items) {
            if (it.type == ItemType.Conversation && (it.summaryStatus == "pending" || it.summaryStatus == "stale")) awaitSummary(it.rawId)
        }
    }

    /** A long conversation is summarised in the background: weigh and save again once it is. */
    private fun awaitSummary(id: String) {
        if (!summaryWaits.add(id)) return
        scope.launch {
            try {
                quietly { api.awaitSummary(id) } ?: return@launch
                if (_state.value.items.any { it.type == ItemType.Conversation && it.rawId == id }) edit { it }
            } finally {
                summaryWaits.remove(id)
            }
        }
    }

    // ── Books followed at the reading position ─────────────

    /** Calibre's books only: a book id that is not a number asks nothing (as on iOS). */
    fun setReadingPaused(bookId: String, paused: Boolean) = scope.launch {
        if (bookId.toIntOrNull() == null) return@launch
        quietly { api.setReadingPaused(bookId, paused) }
        edits.withLock { reweigh() }
    }

    fun resetReadingProgress(bookId: String) = scope.launch {
        if (bookId.toIntOrNull() == null) return@launch
        quietly { api.resetReadingProgress(bookId) }
        edits.withLock { reweigh() }
    }

    // ── For the cards and the picker ───────────────────────

    suspend fun resolveNote(item: TrayItem) = quietly { api.resolveNote(item) }
    suspend fun resolveFolder(item: TrayItem) = quietly { api.resolveFolder(item) }

    /** Asked once per book: an answer is kept, even an empty one; a failure is asked again (`bookChapters`). */
    suspend fun bookChapters(bookId: String): List<BookChapter> =
        chapters[bookId] ?: quietly { api.chapters(bookId) }?.also { chapters[bookId] = it } ?: emptyList()

    /** Null when the request did not complete — not the same as nothing found. */
    suspend fun search(q: String): List<SearchEntry>? = quietly { api.search(q) }

    suspend fun conversationFamilies(id: String) = quietly { api.conversationFamilies(id) }
    suspend fun setConversationFamilies(id: String, families: List<String>?) = quietly { api.setConversationFamilies(id, families) } != null

    /** Put the store in a given state without a server — previews and screenshots only. */
    fun preview(state: ComposerState) { _state.value = state }

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
}
