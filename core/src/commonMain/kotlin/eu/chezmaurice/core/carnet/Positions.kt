package eu.chezmaurice.core.carnet

import eu.chezmaurice.core.model.parseServerDate
import eu.chezmaurice.core.net.MauriceJson
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Where the reader last left off, per book or article (`ReadingPositionStore`):
 * chapter, view, the top character of the text and the top paragraph of the
 * summary. Written on the device first, so resuming works offline; the book
 * reader also sends it to the server and takes the server's when another
 * device read on since. One store per member of a household — two members
 * on one device do not share a place in a book.
 */
class Positions(private val read: () -> String?, private val write: (String) -> Unit) {
    @Serializable
    data class Record(
        val chapterIndex: Int = 0,
        val chapterSlug: String = "",
        /** full | summary: which view to resume. */
        val lastView: String = "full",
        val fullChar: Int = 0,
        val summaryPara: Int = 0,
        /** Epoch milliseconds, this device's clock. */
        val updatedAt: Long = 0,
    )

    @Serializable
    private data class Stored(val records: Map<String, Record> = emptyMap(), val paused: Set<Int> = emptySet())

    private fun load(): Stored = read()?.let { runCatching { MauriceJson.decodeFromString<Stored>(it) }.getOrNull() } ?: Stored()
    private fun store(s: Stored) = write(MauriceJson.encodeToString(Stored.serializer(), s))

    fun get(bookId: Int): Record? = get(bookId.toString())
    /** Articles share the store under `article:<locale>/<slug>`. */
    fun get(key: String): Record? = load().records[key]
    fun save(bookId: Int, record: Record) = save(bookId.toString(), record)
    fun save(key: String, record: Record) { val s = load(); store(s.copy(records = s.records + (key to record))) }

    /** The reset has to clear this as well as the server's row: the local record is the one resumed from. */
    fun clear(bookId: Int) { val s = load(); store(s.copy(records = s.records - bookId.toString())) }

    fun isPaused(bookId: Int): Boolean = bookId in load().paused
    fun setPaused(bookId: Int, paused: Boolean) { val s = load(); store(s.copy(paused = if (paused) s.paused + bookId else s.paused - bookId)) }

    /**
     * Take the server's place when it is clearly newer than this device's:
     * a device that resumed from its own stale copy and wrote it back used to
     * move every other device back with it. The 90 s margin tolerates clock
     * skew and this device's own write racing the read.
     */
    @OptIn(ExperimentalTime::class)
    fun reconcile(bookId: Int, progress: ReadingProgress?, now: Long = Clock.System.now().toEpochMilliseconds()): Record? {
        val local = get(bookId)
        if (progress == null || progress.paused) return local
        val slug = progress.chapterSlug ?: return local
        val index = progress.chapterIndex ?: return local
        val serverAt = parseServerDate(progress.updatedAt)?.toEpochMilliseconds()
        val newer = local == null || (serverAt != null && serverAt > local.updatedAt + 90_000)
        if (!newer) return local
        val view = progress.view ?: "full"
        // Half away from zero, as Swift's rounded(): -2.5 is -3.
        val pos = (progress.position ?: 0.0).let { kotlin.math.sign(it) * kotlin.math.floor(kotlin.math.abs(it) + 0.5) }.toInt()
        // The server keeps one position, the active view's; the other view's
        // stays when it is the same chapter.
        val same = local?.chapterSlug == slug
        val adopted = Record(
            chapterIndex = index, chapterSlug = slug, lastView = view,
            fullChar = if (view == "full") pos else if (same) local!!.fullChar else 0,
            summaryPara = if (view == "summary") pos else if (same) local!!.summaryPara else 0,
            updatedAt = serverAt ?: now,
        )
        save(bookId, adopted)
        return adopted
    }
}
