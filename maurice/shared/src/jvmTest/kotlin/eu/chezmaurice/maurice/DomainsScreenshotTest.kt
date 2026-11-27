package eu.chezmaurice.maurice

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import eu.chezmaurice.core.chat.ChatApi
import eu.chezmaurice.core.chat.ChatController
import eu.chezmaurice.core.chat.ChatState
import eu.chezmaurice.core.domains.DomainStore
import eu.chezmaurice.core.domains.DomainsApi
import eu.chezmaurice.core.domains.DomainsState
import eu.chezmaurice.core.model.BriefStamp
import eu.chezmaurice.core.model.Conversation
import eu.chezmaurice.core.model.DataBlock
import eu.chezmaurice.core.model.Domain
import eu.chezmaurice.core.model.DomainProposal
import eu.chezmaurice.core.model.DomainSummary
import eu.chezmaurice.core.model.Household
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.model.Message
import eu.chezmaurice.core.model.ModelInfo
import eu.chezmaurice.core.model.Role
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.maurice.chat.HomeLayout
import eu.chezmaurice.maurice.domains.DomainsNav
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.maurice.domains.LocalDomains
import eu.chezmaurice.maurice.domains.LocalDomainsNav
import eu.chezmaurice.ui.hexColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

/** The domain screens: the list with the night's proposals, a greeting, the marks. */
class DomainsScreenshotTest {
    private val nadia = Member("u2", "Nadia", "#a6452e", hasPin = true, role = Role.Standard)
    private val household = Household(id = "h1", serverUrl = "https://maison.test", name = "Famille Martin", color = "#2c5aa0", members = listOf(nadia), activeMemberId = "u2")

    private val jardin = Domain("d1", "Le jardin", icon = "leaf", tagline = "Le potager, les semis, la taille des rosiers.", createdBy = "u2", count = 3, weight = 12_400)
    private val italie = Domain("d2", "L'Italie", icon = "airplane", tagline = "Voyages, langue, recettes.", createdBy = "u2", temp = 0.8)
    private val sante = Domain("d3", "Santé", icon = "cross.case", createdBy = "u2")
    private val livre = Domain("k1", "Le Guépard", kind = "companion", createdBy = "u2")

    private val domainsState = DomainsState(
        rows = listOf(italie, jardin, livre, sante),
        overview = mapOf(
            "d1" to DomainSummary("d1", brief = BriefStamp("2026-10-20 03:00:00")),
            "d2" to DomainSummary("d2", brief = BriefStamp("2026-10-01 10:00:00", model = "member")),
            "k1" to DomainSummary("k1", kind = "companion", conversationId = "c9"),
        ),
        proposals = listOf(
            DomainProposal("p1", "Le potager", oneLine = "Semis, arrosage, la récolte de septembre.", conversations = 7, weight = 4, recent90Days = 5, isNew = true, createdAt = "2026-10-21 03:10:00"),
            DomainProposal("p2", "Les vacances", oneLine = "Billets, logements, l'itinéraire de l'été.", conversations = 3, mailThreads = 12, weight = 2, verdict = "lived", to = "2026-06-30", createdAt = "2026-10-21 03:10:00"),
        ),
        unseen = 1,
        models = listOf(ModelInfo("m1", "DeepSeek V4 Flash", provider = "scaleway", isDefault = true), ModelInfo("m2", "Qwen 3.6", provider = "scaleway")),
    )

    private val conversations = listOf(
        Conversation("c1", title = "Semis de tomates en godets", mauriceId = "d1", updatedAt = "2026-10-21 18:00:00"),
        Conversation("c2", title = "Itinéraire Toscane, troisième jour", mauriceId = "d2", mauriceBoundBy = "auto", updatedAt = "2026-10-21 09:00:00"),
        Conversation("c3", title = "Lettre à la mairie", updatedAt = "2026-10-20 21:00:00"),
        Conversation("c9", title = "Chapitre 3 : le bal", mauriceId = "k1", updatedAt = "2026-10-19 08:00:00"),
    )

    private val scope = CoroutineScope(Dispatchers.Default)
    private val client = ApiClient("https://maison.test", "t")

    private fun home(state: ChatState, showingChat: Boolean, nav: DomainsNav = DomainsNav()): @Composable () -> Unit = {
        val controller = remember { ChatController(ChatApi(client), scope, "u2").also { it.seed(state) } }
        val store = remember { DomainStore(DomainsApi(client), scope).also { it.seed(domainsState) } }
        CompositionLocalProvider(LocalDomains provides store, LocalDomainsNav provides nav, LocalAccent provides hexColor(nadia.avatarColor)) {
            Surface(Modifier.fillMaxSize()) { HomeLayout(state, household, nadia, controller, showingChat, {}, {}) }
        }
    }

    @Test fun listWithMarks() {
        val state = ChatState(conversations = conversations, activeId = "c1")
        for (look in listOf(Looks.day, Looks.night)) snapshot("domains-conversations", Frames.phone, look, home(state, showingChat = false))
    }

    @Test fun sheet() {
        val state = ChatState(conversations = conversations)
        for (frame in listOf(Frames.phone, Frames.desktop)) for (look in listOf(Looks.day, Looks.night)) {
            snapshot("domains-sheet", frame, look, home(state, showingChat = false, nav = DomainsNav().apply { showList = true }))
        }
    }

    @Test fun greetingAndPastilles() {
        val armed = ChatState(conversations = conversations, armedMauriceId = "d1")
        val read = DataBlock("domain_brief", MauriceJson.parseToJsonElement("""{"domain_id":"d2","domain":"L'Italie"}"""))
        val recognised = DataBlock("domain_recognised", MauriceJson.parseToJsonElement("""{"domain_id":"d3","domain":"Santé"}"""))
        val bound = ChatState(
            conversations = conversations, activeId = "c1",
            messages = listOf(
                Message("m1", role = "user", content = "On sème quand, les tomates ?", authorId = "u2"),
                Message("m2", role = "assistant", content = "Fin février au chaud, en godets.", data = listOf(read, recognised)),
            ),
        )
        for (frame in listOf(Frames.phone, Frames.desktop)) for (look in listOf(Looks.day, Looks.night)) {
            snapshot("domain-greeting", frame, look, home(armed, showingChat = true))
            snapshot("domain-pastilles", frame, look, home(bound, showingChat = true))
        }
    }
}
