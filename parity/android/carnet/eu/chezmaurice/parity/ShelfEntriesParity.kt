package eu.chezmaurice.parity

import eu.chezmaurice.carnet.entry.whenOf
import eu.chezmaurice.core.carnet.Shelf
import kotlinx.coroutines.delay
import kotlin.test.Test

/** The list of entries and one entry (lot "Carnet shelf & entries"); `ShelfEntriesParity.swift`. */
class ShelfEntriesParity {
    @Test fun shelfDecode() = Parity.check("carnet", "shelf-decode") { c ->
        try {
            val r = Shelf.parse(c.text())
            mapOf(
                "garden" to mapOf("username" to r.garden.username, "site" to r.garden.site),
                "kinds" to r.kinds.map { mapOf("kind" to it.kindWire, "label" to it.kind?.label, "count" to it.count) },
                "entries" to r.entries.map(ShelfObserve::entry),
            )
        } catch (e: Throwable) {
            mapOf("failed" to ShelfFixture.failure(e))
        }
    }

    @Test fun shelfReads() = Parity.check("carnet", "shelf-reads") { c ->
        val garden = ShelfFixture.garden()
        ShelfFixture.exchange {
            when (c.string("call")) {
                "shelf" -> {
                    val r = garden.shelf(archived = c.bool("archived") ?: false).first
                    mapOf("username" to r.garden.username, "site" to r.garden.site,
                        "entries" to r.entries.map { it.id }, "kinds" to r.kinds.map { "${it.kindWire}=${it.count}" })
                }
                "notes" -> {
                    val r = garden.notes()
                    mapOf("entries" to r.entries.map { it.id },
                        "notes" to r.notes.map { mapOf("id" to it.id, "entryId" to it.entryId, "item" to ShelfObserve.item(it.item)) })
                }
                "entry" -> garden.entry(ShelfFixture.entry(c))?.let(ShelfObserve::written)
                "entryNamed" -> ShelfObserve.written(garden.entry(c.string("collection")!!, c.string("locale")!!, c.string("slug")!!))
                "conversations" -> garden.conversations(ShelfFixture.entry(c)).map { ShelfObserve.conversation(it, whenOf(it.updatedAt)) }
                "deploy" -> garden.deployState().let { mapOf("status" to it.status, "error" to it.error, "inProgress" to it.inProgress, "failed" to it.failed) }
                else -> "unknown call"
            }
        }
    }

    @Test fun shelfWrites() = Parity.check("carnet", "shelf-writes") { c ->
        val garden = ShelfFixture.garden()
        ShelfFixture.exchange {
            when (c.string("call")) {
                "addNote" -> ShelfObserve.written(garden.addNote(ShelfFixture.entry(c), c.string("text") ?: "", c.string("quote"), c.string("page")))
                "addBookNote" -> { garden.addBookNote(c.int("bookId")!!, c.string("text") ?: "", null, c.string("chapterTitle")); "done" }
                "writeShared" -> ShelfObserve.written(garden.writeShared(ShelfFixture.entry(c), c.string("body") ?: "", c.string("title")))
                "publish" -> ShelfObserve.written(garden.setPublished(ShelfFixture.entry(c), c.bool("on") ?: true))
                "archive" -> { garden.setArchived(ShelfFixture.entry(c), c.bool("on") ?: true); "done" }
                "delete" -> { garden.delete(ShelfFixture.entry(c)); "done" }
                "share" -> ShelfObserve.written(garden.share(ShelfFixture.entry(c), c.string("member")!!))
                "unshare" -> garden.unshare(ShelfFixture.entry(c), c.string("member")!!)?.let(ShelfObserve::written)
                "refreshDetails" -> ShelfObserve.written(garden.refreshDetails(ShelfFixture.entry(c)))
                "summaries" -> garden.summaries(c.string("slug")!!, c.string("locale")!!, c.bool("force") ?: false)
                    .let { mapOf("text" to it.text, "long" to it.long, "current" to it.current) }
                "conversationSummary" -> garden.conversationSummary(ShelfFixture.entry(c), c.string("conversation")!!, c.bool("force") ?: false)
                    .let { mapOf("id" to it.id, "text" to it.text, "writtenAt" to it.writtenAt, "stale" to it.stale) }
                "setArticleDomain" -> garden.setArticleDomain(c.string("slug")!!, c.string("locale"), c.string("domain"))
                    ?.let { mapOf("id" to it.id, "name" to it.name, "icon" to it.icon) }
                else -> "unknown call"
            }
        }
    }

    @Test fun shelfSessionRefused() = Parity.check("carnet", "shelf-session-refused") { _ ->
        val session = ShelfFixture.session()
        val garden = ShelfFixture.garden(session)
        val ex = ShelfFixture.exchange { garden.shelf().first.entries.map { it.id } }
        delay(50)
        val s = session.state.value
        mapOf(
            "exchange" to ex,
            "signedIn" to (s.activeMember != null),
            "activeMember" to s.current?.activeMemberId,
            "households" to s.households.size,
        )
    }
}
