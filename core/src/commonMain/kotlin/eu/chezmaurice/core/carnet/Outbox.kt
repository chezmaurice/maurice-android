package eu.chezmaurice.core.carnet

import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.net.MauriceJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * What was shared to Carnet and has not reached the household yet
 * (`OfflineQueue`): kept on the device, one list per member of a household,
 * sent when the household answers again. An item leaves the list only once
 * the server took it; whatever failed is tried again next time, as on iOS.
 */
class Outbox(private val read: () -> String?, private val write: (String) -> Unit) {
    @Serializable
    data class Item(val id: String, val url: String, val title: String? = null, val comment: String? = null, val locale: String = "fr", val at: Long = 0)

    private val serializer = ListSerializer(Item.serializer())

    fun items(): List<Item> = read()?.let { runCatching { MauriceJson.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    fun add(item: Item) = write(MauriceJson.encodeToString(serializer, items().filterNot { it.id == item.id } + item))
    fun remove(id: String) = write(MauriceJson.encodeToString(serializer, items().filterNot { it.id == id }))

    /** Each item in turn, oldest first; the number sent. See [flushAll]. */
    suspend fun flush(send: suspend (Item) -> Unit): Int = flushAll(listOf(this to send))

    companion object {
        /**
         * Several outboxes in one round (`SyncService.syncNow`): every item,
         * oldest first whatever its box, sent by its own box's sender (its
         * member, its household). A failure keeps the item for the next round;
         * a session refused (401) ends that box's round, its member being
         * signed out. The number sent.
         */
        suspend fun flushAll(boxes: List<Pair<Outbox, suspend (Item) -> Unit>>): Int {
            val queue = boxes.flatMap { (box, send) -> box.items().map { Triple(it, box, send) } }.sortedBy { it.first.at }
            val refused = mutableSetOf<Outbox>()
            var sent = 0
            for ((item, box, send) in queue) {
                if (box in refused) continue
                try { send(item); box.remove(item.id); sent++ } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                    if ((e as? ApiError)?.status == 401) refused += box
                }
            }
            return sent
        }

        /** A refusal no second try will change: a 4xx other than 401/403/408/429. Told at once to the member saving. */
        fun refusedForGood(e: Throwable): Boolean {
            val status = (e as? ApiError)?.status ?: return false
            return status in 400..499 && status !in setOf(401, 403, 408, 429)
        }
    }
}
