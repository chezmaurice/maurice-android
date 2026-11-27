package eu.chezmaurice.maurice.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import eu.chezmaurice.core.chat.PeopleApi
import eu.chezmaurice.core.chat.PersonRef
import eu.chezmaurice.core.chat.PersonReviewModel
import eu.chezmaurice.core.session.Session
import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.launch

/**
 * Where a link in a reply goes — `ChatView.openLink` on iOS:
 * - a person fiche of the garden (`/g/<member>/<locale>/fiches/people/<slug>`,
 *   the "À vérifier" footer's links) opens in the app, through [reviewFiche];
 * - a page of this household's site (`/g/…`, or a bare server path) opens
 *   in the browser signed in, through `/login?token=…&to=…`;
 * - `maurice-mail:<id>` (in person fiches) opens that mail, signed in;
 * - anything else opens as it is.
 */
class MauriceLinks(
    private val session: Session,
    private val platform: UriHandler,
    private val reviewFiche: ((PersonRef) -> Unit)? = null,
    private val launch: (suspend () -> Unit) -> Unit,
) : UriHandler {
    override fun openUri(uri: String) {
        val server = session.state.value.current?.serverUrl?.trimEnd('/') ?: return platform.openUri(uri)
        val path: String? = when {
            // Decoded, then encoded whole: an id holds '/', '@', '<'… (`ficheLink`).
            uri.startsWith("maurice-mail:") -> "/api/people/mail/" +
                uri.removePrefix("maurice-mail:").let { runCatching { it.decodeURLPart() }.getOrDefault(it) }.encodeURLParameter()
            uri.startsWith("/") -> uri
            sameHost(uri, server) -> runCatching { Url(uri) }.getOrNull()?.let { u -> u.encodedPathAndQuery + (u.encodedFragment.takeIf { it.isNotEmpty() }?.let { "#$it" } ?: "") }
            else -> null
        }
        if (path == null) return platform.openUri(uri)
        // A person fiche is reviewed here, not on the web.
        if (reviewFiche != null) PersonRef.fromGardenUrl(path)?.let { return reviewFiche.invoke(it) }
        launch {
            val signed = runCatching { session.signedInUrl(path) }.getOrNull()
            platform.openUri(signed ?: (server + path))
        }
    }

    private fun sameHost(uri: String, server: String): Boolean {
        val a = runCatching { Url(uri) }.getOrNull() ?: return false
        val b = runCatching { Url(server) }.getOrNull() ?: return false
        return a.host == b.host && a.port == b.port
    }
}

/**
 * Replies open their links through [MauriceLinks] wherever a session is at
 * hand, and a person fiche opens here ([LocalPersonReview]).
 */
@Composable
fun ProvideMauriceLinks(session: Session, content: @Composable () -> Unit) {
    val platform = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var reviewing by remember { mutableStateOf<Pair<PersonRef, (PersonReviewModel?) -> Unit>?>(null) }
    val links = remember(session, platform) {
        MauriceLinks(session, platform, reviewFiche = { ref -> reviewing = ref to { _ -> } }) { block -> scope.launch { block() } }
    }
    // Inside a fiche, its links go to the browser, signed in.
    val gardenLinks = remember(session, platform) { MauriceLinks(session, platform) { block -> scope.launch { block() } } }
    val open: (PersonRef, (PersonReviewModel?) -> Unit) -> Unit = remember { { ref, onChange -> reviewing = ref to onChange } }
    CompositionLocalProvider(LocalUriHandler provides links, LocalPersonReview provides open) {
        content()
        reviewing?.let { (ref, onChange) ->
            val api = remember(session) { session.api()?.let(::PeopleApi) }
            if (api != null) {
                val username = session.state.value.activeMember?.username.orEmpty()
                PersonReviewSheet(
                    ref, api, webPath = "/g/$username/${ref.locale}/fiches/people/${ref.basename}", gardenLinks = gardenLinks,
                    onChange = onChange, onDismiss = { reviewing = null },
                )
            }
        }
    }
}
