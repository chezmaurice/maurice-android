package eu.chezmaurice.core

import eu.chezmaurice.core.carnet.Positions
import eu.chezmaurice.core.carnet.ReadingProgress
import eu.chezmaurice.core.carnet.charOffsetOfParagraph
import eu.chezmaurice.core.carnet.paragraphOfChar
import eu.chezmaurice.core.model.parseServerDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class ReadingTest {
    private fun store(): Positions { var text: String? = null; return Positions({ text }, { text = it }) }
    private fun at(raw: String) = parseServerDate(raw)!!.toEpochMilliseconds()

    @Test fun aServerPlaceClearlyNewerIsTaken() {
        val p = store()
        p.save(7, Positions.Record(1, "ch-1", "full", fullChar = 400, summaryPara = 3, updatedAt = at("2026-10-22 10:00:00")))
        val r = p.reconcile(7, ReadingProgress(chapterIndex = 2, chapterSlug = "ch-2", view = "full", position = 120.0, updatedAt = "2026-10-22 10:05:00"))!!
        assertEquals("ch-2", r.chapterSlug); assertEquals(120, r.fullChar); assertEquals(0, r.summaryPara)
        assertEquals(r, p.get(7))
    }

    @Test fun withinTheMarginTheDeviceKeepsItsOwn() {
        val p = store()
        p.save(7, Positions.Record(1, "ch-1", "full", fullChar = 400, updatedAt = at("2026-10-22 10:00:00")))
        val r = p.reconcile(7, ReadingProgress(chapterIndex = 2, chapterSlug = "ch-2", view = "full", position = 0.0, updatedAt = "2026-10-22 10:01:00"))!!
        assertEquals("ch-1", r.chapterSlug)
    }

    @Test fun sameChapterKeepsTheOtherViewsPlace() {
        val p = store()
        p.save(7, Positions.Record(1, "ch-1", "full", fullChar = 400, summaryPara = 0, updatedAt = 0))
        val r = p.reconcile(7, ReadingProgress(chapterIndex = 1, chapterSlug = "ch-1", view = "summary", position = 4.0, updatedAt = "2026-10-22 10:01:00"))!!
        assertEquals(400, r.fullChar); assertEquals(4, r.summaryPara); assertEquals("summary", r.lastView)
    }

    @Test fun aPausedServerIsIgnored() {
        val p = store()
        assertEquals(null, p.reconcile(7, ReadingProgress(chapterIndex = 1, chapterSlug = "ch-1", enabled = 0, updatedAt = "2026-10-22 10:01:00")))
        p.setPaused(7, true); assertTrue(p.isPaused(7)); p.setPaused(7, false); assertFalse(p.isPaused(7))
    }

    @Test fun paragraphsAreCountedInUtf16() {
        // An emoji is two UTF-16 units: the offsets must count it so.
        val text = "Un 😀 début\n\nSecond\n  \nTroisième"
        assertEquals(0, charOffsetOfParagraph(text, 0))
        assertEquals(13, charOffsetOfParagraph(text, 1))
        assertEquals(23, charOffsetOfParagraph(text, 2))
        assertEquals(1, paragraphOfChar(text, 15))
        assertEquals(2, paragraphOfChar(text, 99))
    }
}

class OutboxTest {
    private fun box(): eu.chezmaurice.core.carnet.Outbox { var text: String? = null; return eu.chezmaurice.core.carnet.Outbox({ text }, { text = it }) }
    private fun item(id: String) = eu.chezmaurice.core.carnet.Outbox.Item(id, "https://example.org/$id")

    @Test fun whatWasSentLeavesWhatFailedStays() = kotlinx.coroutines.test.runTest {
        val b = box()
        b.add(item("a")); b.add(item("b")); b.add(item("c"))
        val sent = b.flush { if (it.id == "b") throw RuntimeException("offline") }
        assertEquals(2, sent)
        assertEquals(listOf("b"), b.items().map { it.id })
    }

    @Test fun aRefusalStaysForNextTime() = kotlinx.coroutines.test.runTest {
        val b = box()
        b.add(item("bad")); b.add(item("busy"))
        b.flush { throw eu.chezmaurice.core.net.ApiError(if (it.id == "bad") 422 else 429, "no") }
        assertEquals(listOf("bad", "busy"), b.items().map { it.id })
    }

    @Test fun oldestFirstAndA401EndsThatMembersRound() = kotlinx.coroutines.test.runTest {
        val mine = box(); val theirs = box()
        mine.add(item("new").copy(at = 30)); mine.add(item("old").copy(at = 10))
        theirs.add(item("mid").copy(at = 20))
        val tried = mutableListOf<String>()
        eu.chezmaurice.core.carnet.Outbox.flushAll(listOf(
            mine to { it: eu.chezmaurice.core.carnet.Outbox.Item -> tried += it.id; throw eu.chezmaurice.core.net.ApiError(401, "revoked") },
            theirs to { it: eu.chezmaurice.core.carnet.Outbox.Item -> tried += it.id },
        ))
        assertEquals(listOf("old", "mid"), tried)
        assertEquals(listOf("new", "old"), mine.items().map { it.id })
        assertEquals(emptyList(), theirs.items())
    }
}
