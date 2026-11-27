package eu.chezmaurice.maurice.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What another app handed to Maurice through the system's Share menu. */
data class SharedDraft(
    /** The address of a page, when one came. */
    val url: String? = null,
    /** The page's title, or a subject line. */
    val title: String? = null,
    /** Text that is not just the address. */
    val text: String? = null,
    val images: List<ByteArray> = emptyList(),
)

/**
 * The share waiting to be taken — `ShareInbox.swift`, without the file: the
 * Android activity that receives the share is the app itself. The home screen
 * opens a fresh conversation; its composer turns the draft into cards.
 */
object ShareInbox {
    private val _pending = MutableStateFlow<SharedDraft?>(null)
    val pending: StateFlow<SharedDraft?> = _pending.asStateFlow()

    fun put(draft: SharedDraft) { _pending.value = draft }

    /** The draft, once; null when there is none. */
    fun take(): SharedDraft? = _pending.value.also { _pending.value = null }

    private val URL = Regex("""https?://\S+""")

    /** Split what a browser or an app shares — "Title https://…", or just the address. */
    fun fromText(text: String?, subject: String?): SharedDraft? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty() && subject.isNullOrBlank()) return null
        val url = URL.find(raw)?.value?.trimEnd('.', ',', ')', '»', '"')
        val rest = if (url != null) raw.replace(url, "").trim() else raw
        return SharedDraft(url = url, title = subject?.trim()?.takeIf { it.isNotEmpty() } ?: rest.takeIf { url != null && it.isNotEmpty() && it.length < 200 }, text = rest.takeIf { url == null && it.isNotEmpty() })
    }
}
