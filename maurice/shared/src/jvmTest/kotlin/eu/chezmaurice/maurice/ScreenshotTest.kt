package eu.chezmaurice.maurice

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import eu.chezmaurice.core.chat.ChatApi
import eu.chezmaurice.core.chat.ChatController
import eu.chezmaurice.core.chat.ChatState
import eu.chezmaurice.core.chat.Streaming
import eu.chezmaurice.core.chat.ToolStep
import eu.chezmaurice.core.model.Conversation
import eu.chezmaurice.core.model.Household
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.model.Message
import eu.chezmaurice.core.model.Role
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.store.MemoryKeyValueStore
import eu.chezmaurice.maurice.chat.HomeLayout
import eu.chezmaurice.maurice.onboarding.MemberPickerScreen
import eu.chezmaurice.maurice.onboarding.PairingScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every main screen, at every size and look. They are not compared to
 * reference images yet: they are there to be looked at, and a screen that
 * throws while laying out fails the build.
 */
class ScreenshotTest {
    private val household = Household(
        id = "h1", serverUrl = "https://maison.test", name = "Famille Martin", color = "#2c5aa0",
        members = listOf(
            Member("u1", "Camille", "#3d6b4f", hasPin = true, role = Role.Admin),
            Member("u2", "Nadia", "#a6452e", hasPin = true, role = Role.Standard),
            Member("u3", "Léon", "#b97a1e", hasPin = false, role = Role.Standard),
        ),
        activeMemberId = "u2",
    )
    private val nadia = household.members[1]

    private val conversations = listOf(
        Conversation("c1", title = "Recette des gnocchi à la sauge", updatedAt = "2026-10-21 18:00:00"),
        Conversation("c2", title = "Itinéraire Toscane, troisième jour", unread = true, updatedAt = "2026-10-21 09:00:00"),
        Conversation("c3", title = "Lettre à la mairie pour le stationnement résidentiel du quartier", updatedAt = "2026-10-20 21:00:00"),
        Conversation("c4", title = null, updatedAt = "2026-10-19 08:00:00"),
    )

    private val reply = """
        Voici la recette, pour **quatre personnes** :

        1. Cuire 1 kg de pommes de terre farineuses à l'eau, dans leur peau.
        2. Les éplucher chaudes, les écraser, ajouter 250 g de farine et un œuf.
        3. Rouler des boudins, couper des tronçons de 2 cm.

        > Le secret : ne pas trop travailler la pâte.

        Pour la sauce, faites mousser `80 g` de beurre avec une dizaine de feuilles de sauge.
    """.trimIndent()

    private val thread = listOf(
        Message("m1", role = "user", content = "Tu peux me redonner la recette des gnocchi ?", authorId = "u2"),
        Message("m2", role = "assistant", content = reply),
        Message("m3", role = "user", content = "Et pour six ?", authorId = "u2"),
    )

    private fun controller() = ChatController(ChatApi(ApiClient("https://maison.test", "t")), CoroutineScope(Dispatchers.Default), "u2")

    private val shots = mutableListOf<java.io.File>()

    private fun home(state: ChatState, showingChat: Boolean = true, live: Streaming? = null) = @androidx.compose.runtime.Composable {
        val controller = androidx.compose.runtime.remember { controller().also { it.seed(state, live) } }
        Surface(Modifier.fillMaxSize()) {
            HomeLayout(state, household, nadia, controller, showingChat, {}, {})
        }
    }

    @Test fun pairing() {
        for (frame in Frames.all) for (look in listOf(Looks.day, Looks.night, Looks.bigText, Looks.english)) {
            shots += snapshot("pairing", frame, look) {
                Surface(Modifier.fillMaxSize()) { PairingScreen(Session(MemoryKeyValueStore(), "maurice")) }
            }
        }
        assertTrue(shots.all { it.length() > 0 })
    }

    @Test fun picker() {
        val session = Session(MemoryKeyValueStore(), "maurice")
        for (frame in Frames.all) for (look in listOf(Looks.day, Looks.night, Looks.bigText)) {
            shots += snapshot("picker", frame, look) {
                Surface(Modifier.fillMaxSize()) { MemberPickerScreen(session, household.copy(activeMemberId = null)) }
            }
        }
    }

    @Test fun conversations() {
        val state = ChatState(conversations = conversations, activeId = "c1")
        for (frame in listOf(Frames.smallPhone, Frames.phone)) for (look in listOf(Looks.day, Looks.night, Looks.bigText)) {
            shots += snapshot("conversations", frame, look, home(state, showingChat = false))
        }
    }

    @Test fun chat() {
        val state = ChatState(conversations = conversations, activeId = "c1", messages = thread)
        for (frame in Frames.all) for (look in listOf(Looks.day, Looks.night, Looks.bigText)) {
            shots += snapshot("chat", frame, look, home(state))
        }
    }

    @Test fun chatStreaming() {
        val state = ChatState(conversations = conversations, activeId = "c1", messages = thread, turnRunning = true)
        val live = Streaming(text = "Pour six personnes, comptez 1,5 kg de pommes de terre", steps = listOf(ToolStep("garden__read_note", 1, true)))
        for (frame in listOf(Frames.phone, Frames.desktop)) for (look in listOf(Looks.day, Looks.night)) {
            shots += snapshot("chat-streaming", frame, look, home(state, live = live))
        }
    }

    @Test fun chatEmptyAndError() {
        val empty = ChatState(conversations = conversations)
        val failed = ChatState(conversations = conversations, activeId = "c1", messages = thread.take(1), error = "Maurice est revenu sans réponse. Réessayez, ou choisissez un autre modèle.")
        for (frame in listOf(Frames.phone, Frames.desktop)) {
            shots += snapshot("chat-empty", frame, Looks.day, home(empty))
            shots += snapshot("chat-error", frame, Looks.day, home(failed))
        }
    }

    @Test fun search() {
        val state = ChatState(
            conversations = conversations, searchQuery = "sauge",
            searchResults = listOf(
                eu.chezmaurice.core.model.SearchHit(conversations[0], "… faites mousser le beurre avec une dizaine de feuilles de ⟦sauge⟧ …", hits = 3),
                eu.chezmaurice.core.model.SearchHit(conversations[1], "… un petit restaurant où les gnocchi à la ⟦sauge⟧ sont …", hits = 1),
            ),
        )
        for (frame in listOf(Frames.phone, Frames.desktop)) for (look in listOf(Looks.day, Looks.night)) {
            shots += snapshot("search", frame, look, home(state, showingChat = false))
        }
    }

    private fun block(tool: String, json: String) = eu.chezmaurice.core.model.DataBlock(tool, eu.chezmaurice.core.net.MauriceJson.parseToJsonElement(json))

    @Test fun cards() {
        val blocks = listOf(
            block("web_search", """{"card":"sources","origin":"web","query":"vin gnocchi sauge","count":14,"results":[
                {"title":"Quel vin avec des gnocchi au beurre de sauge ?","url":"https://www.vins-et-mets.test/gnocchi","snippet":"Un blanc sec et aromatique : soave, pinot grigio du Frioul…"},
                {"title":"Accords mets et vins italiens","url":"https://cuisine.test/accords"}]}"""),
            block("corpus__search", """{"card":"sources","origin":"corpus","results":[{"title":"Gnocchi de la nonna","kind":"note","subtitle":"Recettes"}]}"""),
            block("media__fiche", """{"card":"media","collection":"books","title":"La Cuisine italienne","author":"Marcella Hazan","year":1973,"card_state":"draft","recto_chars":420,"verso_chars":0,"fiche_url":"/g/nadia/fr/fiches/books/cuisine-italienne","card_url":"/g/nadia/fr/cartes/cuisine-italienne"}"""),
            block("life__note", """{"card":"fact","id":"f1","text":"Tu cuisines souvent italien le samedi.","state":"proposed"}"""),
            block("media__search", """{"card":"candidates","collection":"movies","results":[{"id":603,"title":"Big Night","year":1996,"subtitle":"Campbell Scott, Stanley Tucci"},{"id":19,"title":"Mangia","year":2005}]}"""),
            block("people__review", """{"card":"review","footer":"À vérifier : Jeanne Martin.","fiches":[{"title":"Jeanne Martin","locale":"fr","basename":"jeanne-martin","url":"/g/nadia/fr/fiches/people/jeanne-martin","items":[{"kind":"relation","label":"Cousine, vit à Turin"}]}]}"""),
        )
        val reply = eu.chezmaurice.core.model.Message("m9", role = "assistant", content = "Un **soave** ou un **pinot grigio** du Frioul : un blanc sec qui ne couvre pas le beurre.\n\nÀ vérifier : Jeanne Martin.", data = blocks)
        val state = ChatState(conversations = conversations, activeId = "c1", messages = thread.take(1) + reply)
        for (frame in listOf(Frames.tallPhone, Frames.desktop)) for (look in listOf(Looks.day, Looks.night)) {
            shots += snapshot("cards", frame, look, home(state))
        }
    }
}
