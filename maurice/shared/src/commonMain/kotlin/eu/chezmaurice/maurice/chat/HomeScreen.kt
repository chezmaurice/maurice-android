package eu.chezmaurice.maurice.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.chezmaurice.core.chat.ChatApi
import eu.chezmaurice.core.chat.ChatController
import eu.chezmaurice.core.chat.ChatState
import eu.chezmaurice.core.model.Household
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.ui.LocalMauriceTheme
import eu.chezmaurice.maurice.platform.PushRegistrationEffect
import androidx.compose.runtime.CompositionLocalProvider
import eu.chezmaurice.core.domains.DomainStore
import eu.chezmaurice.core.domains.DomainsApi
import eu.chezmaurice.maurice.domains.BriefSheet
import eu.chezmaurice.maurice.domains.DomainsNav
import eu.chezmaurice.maurice.domains.DomainsSheet
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.maurice.domains.LocalDomains
import eu.chezmaurice.maurice.domains.LocalDomainsNav
import eu.chezmaurice.ui.hexColor
import kotlinx.coroutines.launch
import eu.chezmaurice.core.composer.ComposerApi
import eu.chezmaurice.core.composer.ComposerStore
import eu.chezmaurice.maurice.composer.LocalComposer
import eu.chezmaurice.maurice.composer.LocalComposerFactory
import eu.chezmaurice.maurice.domains.DomainEditor
import eu.chezmaurice.maurice.settings.SettingsScreen
import eu.chezmaurice.maurice.settings.Pane
import eu.chezmaurice.maurice.settings.appLanguage
import eu.chezmaurice.maurice.consent.LocalAppLanguage
import eu.chezmaurice.core.budget.BudgetStore
import eu.chezmaurice.core.model.Role
import eu.chezmaurice.core.consent.AiConsentStore
import eu.chezmaurice.maurice.budget.BudgetToast
import eu.chezmaurice.maurice.store.LocalBilling
import eu.chezmaurice.maurice.store.LocalStore
import eu.chezmaurice.maurice.store.StoreController
import eu.chezmaurice.maurice.budget.LocalBudget
import eu.chezmaurice.maurice.budget.LocalOpenBudget
import eu.chezmaurice.maurice.consent.AiConsentScreen
import eu.chezmaurice.maurice.consent.LocalAiConsent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Alignment

/**
 * Signed in: the conversations and the one open. Side by side when the
 * window is wide (tablet, desktop), one at a time on a phone.
 */
@Composable
fun HomeScreen(session: Session, household: Household, member: Member) {
    val scope = rememberCoroutineScope()
    // One controller per member; a switch starts afresh, as on iOS.
    val controller = remember(household.id, member.id) {
        session.api()?.let { ChatController(ChatApi(it), scope, member.id, client = it) }
    } ?: return
    val state by controller.state.collectAsState()
    // The member's domains, companions and the night's proposals.
    val domains = remember(household.id, member.id) {
        session.api()?.let { DomainStore(DomainsApi(it), scope) }
    } ?: return
    val nav = remember(household.id, member.id) { DomainsNav() }
    // The context tray of the conversation on screen; a blank page's goes with its first send.
    val composer = remember(household.id, member.id) {
        session.api()?.let { api ->
            ComposerStore(ComposerApi(api), scope).also { c ->
                controller.onCreated = { id -> c.carryTo(id) }
                c.uploader = { file -> uploadToLibrary(api, file) }
            }
        }
    } ?: return
    val domainsState by domains.state.collectAsState()
    val bound = domainsState.row(state.mauriceId)
    LaunchedEffect(composer, state.activeId, bound?.id, bound?.count, bound?.weight) {
        composer.bind(state.activeId, lockedName = bound?.name.orEmpty(), lockedCount = bound?.count ?: 0, lockedWeight = bound?.weight ?: 0)
    }
    // On a phone: the list, or the chat.
    var showingChat by remember(household.id, member.id) { mutableStateOf(false) }

    LaunchedEffect(controller) { controller.loadConversations() }
    LaunchedEffect(domains) { domains.load() }
    // What the member chose on another device: language and look. The server
    // hears the device's language and what the app runs on in return.
    val deviceLanguage = androidx.compose.ui.text.intl.Locale.current.language
    LaunchedEffect(household.id, member.id) { session.loadServerPrefs(deviceLanguage) }
    var showSettings by remember(household.id, member.id) { mutableStateOf(false) }
    var settingsPane by remember(household.id, member.id) { mutableStateOf<Pane?>(null) }

    // The member's answer about AI providers, and the household's budget: read
    // for whoever is signed in, again when the app comes back, and the budget
    // after every turn, which is what spends it.
    val consent = remember(household.id, member.id) { session.api()?.let(::AiConsentStore) } ?: return
    val budget = remember(household.id, member.id) {
        session.api()?.let { BudgetStore(it, session.store, "${household.serverUrl}|${member.id}") }
    } ?: return
    val consentState by consent.state.collectAsState()
    val budgetState by budget.state.collectAsState()
    LaunchedEffect(consent, budget) {
        launch { consent.refresh() }
        launch { budget.refresh() }
    }
    LaunchedEffect(budget, state.turnRunning) { if (!state.turnRunning) budget.refresh() }
    val openBudget = { settingsPane = Pane.Budget; showSettings = true }

    // The admin of a hosted household buys here, in the Play build only: the
    // controller asks the household whether it has a store, then sends on
    // whatever Play holds that the tower has not counted yet.
    val billing = LocalBilling.current
    val store = remember(household.id, member.id, member.role, billing) {
        if (billing == null || member.role != Role.Admin) null
        else session.api()?.let { StoreController(it, billing, session.store, it.http, deviceLanguage) }
    }
    val storeState = store?.state?.collectAsState()?.value
    LaunchedEffect(store) {
        store ?: return@LaunchedEffect
        store.refresh()
        store.settleLeftovers()
    }
    // The toast: Abonnement for the admin who can top up, Budget for everyone else.
    val openFromToast = {
        settingsPane = if (storeState?.hasStore == true && budgetState.budget?.canTopUp == true) Pane.Subscription else Pane.Budget
        showSettings = true
    }

    // Your notes and the ones shared with others, opened in the browser signed in.
    val platformLinks = androidx.compose.ui.platform.LocalUriHandler.current
    val gardens = remember(household.id, member.id) {
        session.api()?.let { GardensHub(eu.chezmaurice.core.gardens.GardensStore(it), session, platformLinks, scope) }
    } ?: return
    LaunchedEffect(gardens) { gardens.store.load() }

    // This device's push address, declared to the households (Android).
    PushRegistrationEffect(session)

    // A notification was tapped: open its conversation.
    val opening by OpenRequest.pending.collectAsState()
    LaunchedEffect(opening) {
        val target = opening ?: return@LaunchedEffect
        if (target.householdTag != null && target.householdTag != household.id) return@LaunchedEffect // MauriceApp switches first
        OpenRequest.take()
        controller.open(target.conversationId)
        showingChat = true
    }

    // Something shared from another app: a fresh conversation, its composer takes the draft.
    val shared by ShareInbox.pending.collectAsState()
    LaunchedEffect(shared) {
        if (shared != null) { controller.startNew(); showingChat = true }
    }

    // The sockets live while the app is in front; in the background the
    // server falls back to a notification, as with the iOS app.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var comebacks by remember { mutableStateOf(0) }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    controller.resumeLive()
                    // The night may have proposed domains while the app slept.
                    scope.launch { domains.loadProposals() }
                    // A provider added while the app was away is asked about.
                    scope.launch { consent.refresh() }
                    scope.launch { budget.refresh() }
                    // The register of interventions, counted again for its orange dot.
                    comebacks++
                }
                Lifecycle.Event.ON_STOP -> controller.pauseLive()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.resumeLive()
        onDispose {
            lifecycle.removeObserver(observer)
            controller.pauseLive()
        }
    }

    // The register of interventions (Settings → My data): what is unread is
    // an orange dot on the way there, never a dialog (iOS AccountEventsWatch).
    val interventions = remember(household.id, member.id) { eu.chezmaurice.maurice.settings.Interventions() }
    eu.chezmaurice.maurice.settings.InterventionsWatch(session, interventions, comebacks)

    ProvideMauriceLinks(session) { CompositionLocalProvider(
        LocalDomains provides domains,
        LocalDomainsNav provides nav,
        LocalComposer provides composer,
        LocalComposerFactory provides { ComposerStore(ComposerApi(session.api()!!), scope) },
        LocalAccent provides hexColor(member.avatarColor),
        LocalAiConsent provides consent,
        LocalBudget provides budget,
        LocalStore provides store,
        LocalOpenBudget provides openBudget,
        LocalAppLanguage provides appLanguage(session),
        LocalTurnCost provides TurnCostPref.of(session.store),
        LocalGardens provides gardens,
        eu.chezmaurice.maurice.settings.LocalInterventions provides interventions,
    ) {
        Box(Modifier.fillMaxSize()) {
            HomeLayout(
                state = state,
                household = household,
                member = member,
                controller = controller,
                showingChat = showingChat,
                onShowChat = { showingChat = it },
                onOpenSettings = { settingsPane = null; showSettings = true },
            )
            // Crossing 20, 10 or 5 % of the household's budget: a toast, over everything.
            val alert = budgetState.alert
            if (alert != null && !consentState.showsQuestion) {
                BudgetToast(budget, alert, onOpen = openFromToast, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp, start = 16.dp, end = 16.dp))
            }
        }
        if (showSettings) SettingsScreen(session, household, member, initialPane = settingsPane) { showSettings = false; settingsPane = null }
        // Before anything is sent to an AI provider: asked of every role, until answered.
        if (consentState.showsQuestion) {
            AiConsentScreen(consent, consentState.providers, consentState.newProviders, renewing = consentState.accepted)
        }
    } }
}

/** The signed-in layout, without the loading — what the screenshot tests render. */
@Composable
fun HomeLayout(
    state: ChatState,
    household: Household,
    member: Member,
    controller: ChatController,
    showingChat: Boolean,
    onShowChat: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    // The domain sheets, over whichever pane is showing.
    val domains = LocalDomains.current
    val nav = LocalDomainsNav.current
    if (domains != null) {
        if (nav.showList) DomainsSheet(domains, controller, member, onOpened = { onShowChat(true) }) { nav.showList = false }
        nav.briefFor?.let { d -> BriefSheet(d, domains, controller, onOpened = { onShowChat(true) }) { nav.briefFor = null } }
        nav.editing?.let { d -> DomainEditor(d, domains, controller, LocalComposerFactory.current, onClose = { nav.editing = null }, onBack = nav::backToList) }
    }
    // A garden's page takes the chat's place while it is open.
    val gardens = LocalGardens.current
    val openGarden = gardens?.store?.state?.collectAsState()?.value?.let { it.garden(it.openGardenId) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val list = @Composable { modifier: Modifier ->
            ConversationList(
                state = state,
                household = household,
                member = member,
                onOpen = { gardens?.store?.open(null); controller.open(it); controller.search(""); onShowChat(true) },
                onNew = { gardens?.store?.open(null); controller.startNew(); onShowChat(true) },
                onOpenGarden = { gardens?.store?.open(it); onShowChat(true) },
                onLoadMore = controller::loadMore,
                onDelete = controller::delete,
                onOpenSettings = onOpenSettings,
                onSearch = controller::search,
                modifier = modifier,
            )
        }
        val chat = @Composable { modifier: Modifier ->
            if (gardens != null && openGarden != null) GardenPage(
                gardens, openGarden,
                onBack = { gardens.store.open(null); if (!wide) onShowChat(false) },
                modifier = modifier,
            ) else ChatScreen(
                state = state,
                household = household,
                member = member,
                controller = controller,
                onBack = if (wide) null else ({ onShowChat(false) }),
                modifier = modifier,
            )
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                list(Modifier.width(320.dp).fillMaxHeight())
                VerticalDivider(color = LocalMauriceTheme.current.rule)
                chat(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                if (showingChat) {
                    BackHandlerCompat { onShowChat(false) }
                    chat(Modifier.fillMaxSize())
                } else list(Modifier.fillMaxSize())
            }
        }
    }
}

/** A document filed at the library's root, as the picker would have found it. */
private suspend fun uploadToLibrary(api: eu.chezmaurice.core.net.ApiClient, file: eu.chezmaurice.core.settings.Upload): eu.chezmaurice.core.composer.SearchEntry {
    val row = eu.chezmaurice.core.settings.SettingsApi(api).upload(file)
    return eu.chezmaurice.core.composer.SearchEntry(eu.chezmaurice.core.composer.ItemType.File, row.id, row.name, kind = row.kind)
}
