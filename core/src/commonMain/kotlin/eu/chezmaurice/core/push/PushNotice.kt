package eu.chezmaurice.core.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * What a household's push says, once the UnifiedPush connector has decrypted
 * it: the JSON `pushToUser` encrypts to the device's keys (server
 * `services/push.ts`) — `title`, `body`, and what the tap opens: a
 * conversation (`conversationId`), or for Carnet an entry another member
 * shared (`entry`, `<collection>/<locale>/<slug>`), with `householdTag`, the
 * device's own id of the household it came from. The same keys iOS reads
 * (Carnet's `NotificationService`, the Maurice app's).
 */
data class PushNotice(
    val title: String?,
    val body: String,
    val conversationId: String? = null,
    val entry: String? = null,
    val householdTag: String? = null,
) {
    /** What a tap on it opens; null: the app as it was (an account event says only what happened). */
    val tap: PushTap?
        get() = conversationId?.let { PushTap.Conversation(it) }
            ?: entry?.takeIf { isEntryRef(it) }?.let { PushTap.Entry(it) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The decrypted bytes' text; null when it is not a JSON object. */
        fun parse(text: String): PushNotice? {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            fun field(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            return PushNotice(
                title = field("title"),
                body = field("body").orEmpty(),
                conversationId = field("conversationId"),
                entry = field("entry"),
                householdTag = field("householdTag"),
            )
        }

        /** `<collection>/<locale>/<slug>`, three parts none of them empty: what `carnet://entry/…` names. */
        fun isEntryRef(ref: String): Boolean = ref.split('/').let { parts -> parts.size == 3 && parts.all { it.isNotEmpty() } }
    }
}

/** Where a notification's tap leads. */
sealed interface PushTap {
    data class Conversation(val id: String) : PushTap
    /** An entry shared with the member, `<collection>/<locale>/<slug>`. */
    data class Entry(val ref: String) : PushTap
}

/**
 * The household a tap should switch to before opening what it names, as
 * iOS does: the one the push came from, when it is paired here and is not
 * already the one on screen. Null: stay where the app is.
 */
fun householdToSwitchTo(tag: String?, pairedIds: Collection<String>, currentId: String?): String? =
    tag?.takeIf { it != currentId && it in pairedIds }
