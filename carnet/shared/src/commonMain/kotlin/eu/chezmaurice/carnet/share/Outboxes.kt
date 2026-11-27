package eu.chezmaurice.carnet.share

import eu.chezmaurice.carnet.platform.Offline
import eu.chezmaurice.carnet.platform.offlineKey
import eu.chezmaurice.carnet.platform.stableHash
import eu.chezmaurice.core.carnet.CalibreApi
import eu.chezmaurice.core.carnet.GardenApi
import eu.chezmaurice.core.carnet.Outbox
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.session.Session
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A member's outbox (`household#member`), kept with the rest of their offline files. */
fun memberOutbox(scope: String): Outbox =
    Outbox({ Offline.read(offlineKey(scope, "outbox")) }, { Offline.write(offlineKey(scope, "outbox"), it) })

/**
 * The EPUBs and PDFs a member shared to Carnet that the household's library
 * has not taken yet (iOS's `.libraryBook` in `OfflineQueue`): the list with
 * the member's offline files, each book as it came, beside them.
 */
class BookOutbox(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val readBytes: (id: String) -> ByteArray?,
    /** Null takes the book's file away. */
    private val writeBytes: (id: String, ByteArray?) -> Unit,
) {
    @Serializable data class Item(val id: String, val filename: String, val at: Long = 0)

    private val serializer = ListSerializer(Item.serializer())

    fun items(): List<Item> = read()?.let { runCatching { MauriceJson.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    fun bytes(id: String): ByteArray? = readBytes(id)

    fun add(item: Item, bytes: ByteArray) {
        writeBytes(item.id, bytes)
        write(MauriceJson.encodeToString(serializer, items().filterNot { it.id == item.id } + item))
    }

    fun remove(id: String) {
        write(MauriceJson.encodeToString(serializer, items().filterNot { it.id == id }))
        writeBytes(id, null)
    }
}

/** A member's book outbox: its list with their offline files, the books under no member's prefix (they outlive a session, as the outbox does). */
fun memberBookOutbox(scope: String): BookOutbox {
    fun blobKey(id: String) = "bookbox-" + stableHash(scope) + "-" + id
    return BookOutbox(
        { Offline.read(offlineKey(scope, "book-outbox")) }, { Offline.write(offlineKey(scope, "book-outbox"), it) },
        { Offline.readBytes(blobKey(it)) },
        { id, bytes -> if (bytes != null) Offline.writeBytes(blobKey(id), bytes) else Offline.deleteAll(blobKey(id) + ".") },
    )
}

/**
 * Every capture this device still holds, sent as `SyncService.syncNow` sends
 * them: oldest first, each to its own household as the member who made it —
 * whoever is signed in now. A member no longer signed in here keeps theirs
 * until they are again. Then the books waiting for a library: one that is
 * refused is dropped (sending it again would say the same), one the library
 * did not answer for waits. The number sent.
 */
suspend fun flushOutboxes(
    session: Session,
    outboxOf: (scope: String) -> Outbox = ::memberOutbox,
    booksOf: (scope: String) -> BookOutbox = ::memberBookOutbox,
): Int {
    val members = session.state.value.households.flatMap { h ->
        (listOfNotNull(h.activeMemberId) + h.members.map { it.id }).distinct().mapNotNull { memberId ->
            session.apiAs(h.id, memberId)?.let { "${h.id}#$memberId" to it }
        }
    }
    val boxes = members.map { (scope, api) ->
        val garden = GardenApi(api)
        val send: suspend (Outbox.Item) -> Unit = { item -> garden.saveArticle(item.url, item.locale, item.comment, item.title) }
        outboxOf(scope) to send
    }
    var sent = Outbox.flushAll(boxes)
    for ((scope, api) in members) {
        val books = booksOf(scope)
        val calibre = CalibreApi(api)
        for (item in books.items().sortedBy { it.at }) {
            val bytes = books.bytes(item.id) ?: run { books.remove(item.id); null } ?: continue
            try {
                calibre.addBook(bytes, item.filename)
                books.remove(item.id); sent++
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (e.status == 401) break
                if (e.status != 0) books.remove(item.id)
            } catch (_: Throwable) {
                // The library did not answer, or the network: next time.
            }
        }
    }
    return sent
}
