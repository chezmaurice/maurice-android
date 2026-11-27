package eu.chezmaurice.maurice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import eu.chezmaurice.maurice.chat.ShareInbox
import eu.chezmaurice.maurice.chat.SharedDraft

/**
 * Read what another app shared (ACTION_SEND / ACTION_SEND_MULTIPLE): a page's
 * address and title, some text, photos. The composer does the rest.
 */
fun takeShare(context: Context, intent: Intent?) {
    val action = intent?.action ?: return
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
    val uris: List<Uri> = when (action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.stream())
        else -> intent.streams()
    }
    val images = uris.mapNotNull { uri -> runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
    val fromText = ShareInbox.fromText(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT))
    val draft = (fromText ?: SharedDraft()).copy(images = images)
    if (draft.url != null || draft.text != null || draft.images.isNotEmpty()) ShareInbox.put(draft)
    // Taken once: a rotation must not share it again.
    intent.action = null
}

private fun Intent.stream(): Uri? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    else @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_STREAM)

private fun Intent.streams(): List<Uri> =
    if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    else @Suppress("DEPRECATION") getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
