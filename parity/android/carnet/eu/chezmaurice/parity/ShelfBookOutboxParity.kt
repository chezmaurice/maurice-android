package eu.chezmaurice.parity

import eu.chezmaurice.carnet.share.BookOutbox
import eu.chezmaurice.carnet.share.flushOutboxes
import eu.chezmaurice.core.carnet.Outbox
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test

/** A book outbox held in memory: the suite never touches the device's offline files. */
object ShelfBooks {
    fun box(list: MutableMap<String, String> = mutableMapOf(), files: MutableMap<String, ByteArray> = mutableMapOf()) = BookOutbox(
        { list["list"] }, { list["list"] = it },
        { files[it] }, { id, bytes -> if (bytes != null) files[id] = bytes else files.remove(id) },
    )
}

/**
 * EPUBs and PDFs shared to Carnet that the library had not taken, sent again
 * (ed512a3: `SyncService`'s `.libraryBook`); `ShelfBookOutboxParity.swift`.
 */
class ShelfBookOutboxParity {
    @Test fun bookOutbox() = Parity.check("carnet", "shelf-book-outbox") { c ->
        val session = ShelfFixture.session()
        val books = ShelfBooks.box()
        c.array("books").orEmpty().forEachIndexed { i, raw ->
            val o = raw.jsonObject
            books.add(
                BookOutbox.Item("b$i", o["filename"]!!.jsonPrimitive.content, 1_800_000_000_000L + (o["at"]?.jsonPrimitive?.longOrNull ?: 0L) * 1000),
                (o["bytes"]?.jsonPrimitive?.content ?: "x").encodeToByteArray(),
            )
        }
        flushOutboxes(session, outboxOf = { Outbox({ null }, {}) }, booksOf = { books })
        mapOf(
            // The multipart body carries each side's own boundary: where each book went, and in what order.
            "requests" to ShelfObserve.requests(prefix = "/api/v1/calibre").map { r -> (r as? JsonObject)?.let { JsonObject(it - "body") } ?: r },
            "left" to books.items().map { it.filename }.sorted(),
        )
    }
}
