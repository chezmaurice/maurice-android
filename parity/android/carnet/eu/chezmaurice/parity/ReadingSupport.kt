package eu.chezmaurice.parity

import eu.chezmaurice.carnet.Navigator
import eu.chezmaurice.carnet.platform.Offline
import eu.chezmaurice.core.carnet.Article
import eu.chezmaurice.core.carnet.Chapter
import eu.chezmaurice.core.carnet.Highlight
import eu.chezmaurice.core.carnet.LinkTarget
import eu.chezmaurice.core.carnet.Positions
import eu.chezmaurice.core.carnet.ReadingProgress
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.session.Session
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The Carnet reading lot's fixture: a blank device paired with the fake
 * household, one member signed in — the Android side of `ReadingSupport.swift`.
 * The session is the app's (`Session(store, "carnet")`), the member's place
 * on the device is a [Navigator] scoped `household#member`, as CarnetApp
 * builds it.
 */
object ReadingFixture {
    const val HOUSEHOLD_ID = MauriceFixture.HOUSEHOLD_ID

    init {
        // Carnet's desktop Offline store writes under ~/.carnet/offline: point
        // it at a scratch folder before it is first touched, so the suite
        // never reads or wipes the developer's own Carnet.
        System.setProperty("user.home", Files.createTempDirectory("parity-reading").toFile().absolutePath)
    }

    /** A blank device, [member] signed in with [token]. */
    fun session(member: String = "u1", token: String = "tok-$member"): Session {
        wipe()
        return MauriceFixture.session(prefix = "carnet", member = member, token = token)
    }

    /** The signed-in member's client. */
    fun api(member: String = "u1"): ApiClient = session(member).api()!!

    /** A client for [member] on the same device, without wiping it. */
    fun apiAs(member: String, baseUrl: String = Parity.baseUrl): ApiClient = ApiClient(baseUrl, "tok-$member")

    fun scope(member: String = "u1", household: String = HOUSEHOLD_ID) = "$household#$member"
    fun navigator(member: String = "u1", household: String = HOUSEHOLD_ID) = Navigator(scope(member, household))

    /** Nothing kept on disk. */
    fun wipe() { Offline.deleteAll("") }
}

object ReadingObs {
    /** The requests the household received, and what came of the call; a failure is `{"thrown": true}`. */
    suspend fun exchange(body: suspend () -> Any?): Map<String, Any?> {
        val result: Any? = try { Parity.toJson(body()) } catch (e: Throwable) {
            if (e is AssertionError) throw e
            mapOf("thrown" to true)
        }
        return mapOf("requests" to Parity.requests(), "result" to result)
    }

    fun epochMillis(iso: String?): Long? = iso?.let { java.time.Instant.parse(it).toEpochMilli() }

    /** Epoch seconds, or "now" for a stamp the code took from the clock. */
    fun stamp(ms: Long): Any = if (abs(ms - System.currentTimeMillis()) < 120_000) "now" else ms / 1000.0

    fun record(r: Positions.Record?): Any? = r?.let {
        mapOf("chapterIndex" to it.chapterIndex, "chapterSlug" to it.chapterSlug, "lastView" to it.lastView,
            "fullChar" to it.fullChar, "summaryPara" to it.summaryPara, "updatedAt" to stamp(it.updatedAt))
    }

    fun chapter(c: Chapter) = mapOf(
        "index" to c.index, "slug" to c.slug, "title" to c.title, "wordCount" to c.wordCount,
        "summaryWordCount" to c.summaryWordCount, "hasSummary" to c.hasSummary, "readingMinutes" to c.readingMinutes,
    )

    fun highlight(h: Highlight) = mapOf(
        "id" to h.id, "chapterSlug" to h.chapterSlug, "quote" to h.quote, "note" to h.note, "color" to h.color,
        "start" to h.startOffset, "end" to h.endOffset, "readerView" to h.readerView,
    )

    fun progress(p: ReadingProgress?): Any? = p?.let {
        mapOf("bookId" to it.bookId, "chapterIndex" to it.chapterIndex, "chapterSlug" to it.chapterSlug,
            "view" to it.view, "enabled" to it.enabled, "position" to it.position, "updatedAt" to it.updatedAt)
    }

    fun article(a: Article) = mapOf(
        "slug" to a.slug, "locale" to a.locale, "title" to a.title, "id" to a.id, "url" to a.url,
        "hasText" to a.hasText, "textRev" to a.textRev, "readableSummary" to a.readableSummary,
        "hasSummary" to a.hasSummary, "readingMinutes" to a.readingMinutes, "bylineShort" to a.bylineShort,
    )

    fun target(t: LinkTarget) = mapOf(
        "collection" to t.collection, "locale" to t.locale, "slug" to t.slug, "title" to t.title, "date" to t.date,
        "linkBasename" to t.linkBasename, "hasFiche" to t.hasFiche,
    )

    fun round3(v: Double): Double = (v * 1000).roundToLong() / 1000.0

    fun JsonElement?.textOrNull(): String? = this?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.toString() }
}
