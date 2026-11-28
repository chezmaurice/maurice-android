package eu.chezmaurice.carnet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.LaunchedEffect
import eu.chezmaurice.carnet.platform.Offline
import eu.chezmaurice.carnet.platform.forgetMember
import eu.chezmaurice.carnet.platform.migrateLegacyOffline
import eu.chezmaurice.carnet.platform.offlineKey
import androidx.compose.runtime.key
import eu.chezmaurice.carnet.shelf.ShelfScreen
import eu.chezmaurice.core.carnet.Positions
import eu.chezmaurice.core.chat.ChatApi
import eu.chezmaurice.core.chat.ChatController
import eu.chezmaurice.core.domains.DomainStore
import eu.chezmaurice.core.domains.DomainsApi
import eu.chezmaurice.maurice.domains.LocalDomains
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import eu.chezmaurice.carnet.ui.LocalApi
import eu.chezmaurice.core.carnet.ShelfEntry
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.maurice.chat.BackHandlerCompat
import eu.chezmaurice.maurice.chat.ProvideMauriceLinks
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.maurice.onboarding.MemberPickerScreen
import eu.chezmaurice.maurice.onboarding.PairingScreen
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.ui.LocalStrings
import eu.chezmaurice.ui.MauriceThemeProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The member's conversations with Maurice, as Carnet holds them. */
val LocalChat = staticCompositionLocalOf<ChatController?> { null }

/** Where Carnet can go from the shelf (`ShelfRoute`). */
sealed interface Route {
    /** [asFiche]: an article's notes and conversations, rather than its page. */
    data class Entry(val entry: ShelfEntry, val asFiche: Boolean = false) : Route
    data class Book(val bookId: Int, val title: String, val byline: String?) : Route
    data class Resume(val bookId: Int, val title: String, val byline: String?) : Route
    data class Read(val slug: String, val locale: String) : Route
    /** The captured text of an article given from another household and shared with the member: it lives in its owner's notes. */
    data class ReadShared(val entry: ShelfEntry) : Route
    /** A book's chapters, open at [index], in its summary if [summary]. */
    data class Reader(val bookId: Int, val title: String, val byline: String?, val chapters: List<eu.chezmaurice.core.carnet.Chapter>, val index: Int, val summary: Boolean) : Route
    data class Highlights(val bookId: Int, val title: String, val byline: String?, val chapters: List<eu.chezmaurice.core.carnet.Chapter>) : Route
    data object Notes : Route
    data object Maurice : Route
    data class Brief(val domainId: String, val name: String) : Route
    /** A conversation, read and answered here; null: the blank page [ChatController.startNew] armed. */
    data class Conversation(val id: String?) : Route
}

/**
 * The pages above the shelf, last on top. [scope] names the member of the
 * household: what is kept on the device for them (their shelf, their place
 * in each book) is kept under it, apart from the other members'.
 */
@Stable
class Navigator(val scope: String) {
    val stack = mutableStateListOf<Route>()
    /** The member's garden, as the shelf last said: their username, and their site if they have one. */
    var username by mutableStateOf("")
    var site by mutableStateOf<String?>(null)
    /** Moved when the shelf has news from elsewhere (a shared page reached the household). */
    var shelfVersion by mutableStateOf(0)
    fun push(route: Route) { stack.add(route) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun reset(vararg routes: Route) { stack.clear(); stack.addAll(routes) }
    val positions = Positions({ Offline.read(offlineKey(scope, "positions")) }, { Offline.write(offlineKey(scope, "positions"), it) })
}

/**
 * What another app or a notification asked Carnet to open:
 * `carnet://entry/<collection>/<locale>/<slug>` (from the Maurice app), or a
 * conversation Maurice opened (from a notification).
 */
object CarnetRouter {
    private val _entry = MutableStateFlow<String?>(null)
    val entry = _entry.asStateFlow()
    private val _conversation = MutableStateFlow<Pair<String?, String>?>(null)
    /** (household tag, conversation id). */
    val conversation = _conversation.asStateFlow()

    fun open(url: String) {
        val parts = url.removePrefix("carnet://").split("/")
        if (parts.size == 4 && parts[0] == "entry") openEntry(parts.drop(1).joinToString("/"))
    }
    /** The entry asked for came from a notification: if it cannot be found, the shelf rather than the page on screen. */
    private var entryOrShelf = false

    /** An entry by its id (`<collection>/<locale>/<slug>`, or `calibre/<id>` for a library book). */
    fun openEntry(ref: String) { entryOrShelf = false; _entry.value = ref }
    /** An entry another member shared, from a notification's tap: the entry, or the shelf if it is not there. */
    fun openSharedEntry(ref: String) { entryOrShelf = true; _entry.value = ref }
    fun openConversation(householdTag: String?, id: String) { _conversation.value = householdTag to id }
    /** Taken by the shelf; true when, not found, the entry should leave the member on the shelf. */
    fun takeEntry(): Boolean { _entry.value = null; return entryOrShelf.also { entryOrShelf = false } }
    fun takeConversation() { _conversation.value = null }
}

/**
 * Carnet: pair a household, choose who you are, then the shelf. The pairing
 * and the member picker are the Maurice app's own, in its words.
 */
@Composable
fun CarnetApp(session: Session) {
    // A member who leaves the device takes what was kept for them.
    remember(session) { session.onMemberGone = { h, m -> forgetMember("$h#$m") } }
    eu.chezmaurice.maurice.onboarding.InvitationLinks.Handle(session)
    eu.chezmaurice.maurice.HouseholdImageLoader(session)
    eu.chezmaurice.maurice.onboarding.HouseholdsRefresh(session)
    val prefs by session.prefs.collectAsState()
    val strings by produceState<eu.chezmaurice.ui.Strings?>(null, prefs.locale) { value = loadCarnetStrings(prefs.locale) }
    val state by session.state.collectAsState()
    MauriceThemeProvider(prefs.palette) {
        Surface(Modifier.fillMaxSize(), color = LocalMauriceTheme.current.bg) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                val loaded = strings
                if (loaded == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    return@Box
                }
                CompositionLocalProvider(
                    LocalStrings provides loaded,
                    eu.chezmaurice.maurice.onboarding.LocalAppBrand provides eu.chezmaurice.maurice.onboarding.AppBrand.Carnet,
                    // The app's language, for what its strings do not hold (dates, countries).
                    eu.chezmaurice.maurice.consent.LocalAppLanguage provides (prefs.locale ?: androidx.compose.ui.text.intl.Locale.current.language),
                ) {
                    val household = state.current
                    val member = state.activeMember
                    // Who controls the household, what stays private: once per server, as in Maurice.
                    var disclosed by remember(household?.id, member?.id) { mutableStateOf(!session.needsConnectDisclosure()) }
                    val leave = rememberCoroutineScope()
                    when {
                        household == null -> PairingScreen(session)
                        member == null -> MemberPickerScreen(session, household)
                        !disclosed -> eu.chezmaurice.maurice.onboarding.ConnectDisclosure(
                            onAccept = { session.acceptConnectDisclosure(); disclosed = true },
                            onCancel = { leave.launch { session.disconnect(household.id) } },
                        )
                        // Everything below is built again for each member of each household:
                        // no list, field or sheet of one is shown to the next.
                        // The address too: a household that moved builds its clients again there.
                        else -> key(household.id, member.id, household.serverUrl) {
                            val api = remember { session.api() }
                            // The language the member chose in Maurice, read again when they become active.
                            LaunchedEffect(Unit) { session.loadServerLocale() }
                            // Push (Android: UnifiedPush; none on the desktop): the conversations
                            // Maurice opens, the entries other members share, as on iOS.
                            eu.chezmaurice.maurice.platform.PushRegistrationEffect(session, platform = "carnet-android", appName = "Carnet")
                            val nav = remember { "${household.id}#${member.id}".let { migrateLegacyOffline(it); Navigator(it) } }
                            if (api != null) ProvideMauriceLinks(session) {
                                val scope = rememberCoroutineScope()
                                // Maurice, as Carnet holds him: the member's conversations and domains.
                                val chat = remember(household.id, member.id) { ChatController(ChatApi(api), scope, member.id, client = api) }
                                val domains = remember(household.id, member.id) { DomainStore(DomainsApi(api), scope) }
                                LaunchedEffect(chat) { chat.probeDictation() }
                                CompositionLocalProvider(
                                    LocalApi provides api, LocalAccent provides LocalMauriceTheme.current.ink,
                                    LocalChat provides chat, LocalDomains provides domains,
                                    // A [[link]] opens the entry it names (`collection/locale/slug`): its page reads the rest.
                                    eu.chezmaurice.carnet.reading.LocalOpenWikiLink provides { link ->
                                        link.entryId?.split('/')?.takeIf { it.size == 3 }?.let { (collection, locale, slug) ->
                                            nav.push(Route.Entry(ShelfEntry(id = link.entryId!!, kindWire = collection, collection = collection, locale = locale, slug = slug, title = link.label)))
                                        }
                                    },
                                ) {
                                    if (nav.stack.isNotEmpty()) BackHandlerCompat { nav.pop() }
                                    // A field focused on one page does not keep the keyboard up over the next.
                                    val focus = LocalFocusManager.current
                                    val keyboard = LocalSoftwareKeyboardController.current
                                    LaunchedEffect(nav.stack.size) { focus.clearFocus(force = true); keyboard?.hide() }
                                    Box(Modifier.fillMaxSize()) {
                                        ShelfScreen(session, nav)
                                        eu.chezmaurice.carnet.share.ShareHost(nav, session)
                                        nav.stack.lastOrNull()?.let { route -> eu.chezmaurice.carnet.ui.FullScreen { RoutePage(session, nav, route) } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
