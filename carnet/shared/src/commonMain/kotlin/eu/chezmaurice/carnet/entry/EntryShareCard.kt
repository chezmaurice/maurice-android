package eu.chezmaurice.carnet.entry

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.chezmaurice.carnet.platform.rememberShareCard
import eu.chezmaurice.carnet.ui.GridIconButton
import eu.chezmaurice.carnet.ui.LocalApi
import eu.chezmaurice.core.carnet.EntryDetail
import eu.chezmaurice.core.carnet.ShelfEntry
import eu.chezmaurice.core.carnet.gardenImageUrl
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.ui.DomainGlyphs
import eu.chezmaurice.ui.L
import eu.chezmaurice.ui.LocalMauriceTheme
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The card of an entry — its shared face, what the member wrote for others —
 * through the system's share sheet (`EntryShareCard.swift`): the poster alone
 * as an image when there is one, "Title — byline", a blank line and the
 * card's text, then its public page if it is published, else the original's
 * link. The fiche, the member's own side, is never shared.
 */
@Composable
fun ShareCardButton(entry: ShelfEntry, shared: EntryDetail.Shared) {
    val api = LocalApi.current
    val share = rememberShareCard()
    val scope = rememberCoroutineScope()
    var preparing by remember(entry.id) { mutableStateOf(false) }
    if (preparing) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalMauriceTheme.current.inkMute)
        }
        return
    }
    GridIconButton(DomainGlyphs.of("square.and.arrow.up"), L("entry.share.card")) {
        preparing = true
        scope.launch {
            try {
                val poster = api?.let { runCatching { poster(it, entry) }.getOrNull() }
                share(poster?.bytes, poster?.mime, posterFileName(entry, poster?.mime), cardShareText(entry, shared))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                println("[carnet] share card: ${e.message}")
            } finally {
                preparing = false
            }
        }
    }
}

/**
 * The card can be shared when it says something, and is published — or,
 * for a member with no site to publish it on, still a draft.
 */
fun cardCanBeShared(shared: EntryDetail.Shared?, hasSite: Boolean): Boolean =
    shared != null && withoutTitle(shared.body, shared.title).isNotBlank() && (shared.published || !hasSite)

/**
 * What the share sheet receives as text: the card ([cardText]), then its link.
 * iOS hands the link to the sheet as an item of its own; an Android share
 * carries a single text, so the link closes it.
 */
fun cardShareText(entry: ShelfEntry, shared: EntryDetail.Shared): String =
    listOfNotNull(cardText(entry, shared), cardLink(entry, shared)).joinToString("\n\n")

/** "Title — byline", a blank line, the card's text in plain words (`CardText.text`). */
fun cardText(entry: ShelfEntry, shared: EntryDetail.Shared): String {
    val title = shared.title?.takeIf { it.isNotBlank() } ?: entry.title
    val byline = entry.byline?.takeIf { it.isNotBlank() }
    val head = if (byline != null) "$title — $byline" else title
    val body = plainCardText(withoutTitle(shared.body, shared.title))
    return listOfNotNull(head, body.ifBlank { null }).joinToString("\n\n")
}

/**
 * Its public page when published, else the original — each only when it is
 * a full http(s) address (`CardText.link`): a relative public url falls back
 * to the original.
 */
fun cardLink(entry: ShelfEntry, shared: EntryDetail.Shared): String? =
    listOf(shared.publicUrl.takeIf { shared.published }, entry.source?.url)
        .firstOrNull { it != null && (it.startsWith("https://") || it.startsWith("http://")) }

/**
 * The card's Markdown as plain words (`CardText.plain`): [[target|label]] →
 * label, [[target]] → target, [text](url) → text, an empty part included;
 * no ** or __.
 */
fun plainCardText(markdown: String): String = markdown
    .replace(Regex("""\[\[[^\]|]*\|([^\]]*)]]""")) { it.groupValues[1] }
    .replace(Regex("""\[\[([^\]]*)]]""")) { it.groupValues[1] }
    .replace(Regex("""\[([^\]]*)]\([^)]*\)""")) { it.groupValues[1] }
    .replace("**", "").replace("__", "")
    .trim()

private class Poster(val bytes: ByteArray, val mime: String)

/** The cover as the page shows it, raw: the garden's image, else the library's (which wants the token). */
private suspend fun poster(api: ApiClient, entry: ShelfEntry): Poster? {
    val garden = gardenImageUrl(api.baseUrl, entry.image)
    val book = entry.source?.bookId
    val url = garden ?: book?.let { api.url("/api/v1/calibre/books/$it/cover") } ?: return null
    val response = api.http.get(url) { if (garden == null) api.token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
    if (!response.status.isSuccess()) return null
    val type = response.contentType()?.let { "${it.contentType}/${it.contentSubtype}" }?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
    return Poster(response.readRawBytes(), type)
}

/** "the-title.jpg": a name the receiving app can show. */
fun posterFileName(entry: ShelfEntry, mime: String?): String {
    val base = entry.title.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
        .split('-').filter { it.isNotEmpty() }.joinToString("-").take(60).ifEmpty { "carnet" }
    val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "jpg" }
    return "$base.$ext"
}
