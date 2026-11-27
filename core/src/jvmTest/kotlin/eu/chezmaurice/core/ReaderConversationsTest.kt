package eu.chezmaurice.core

import eu.chezmaurice.core.carnet.GardenApi
import eu.chezmaurice.core.carnet.Highlight
import eu.chezmaurice.core.carnet.HighlightConversation
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.MauriceJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Maurice from Carnet's readers (maurice c37ec28, 58c6d3e; carnet iOS bdeda19, e3ffaec). */
class ReaderConversationsTest {
    private val sent = mutableListOf<Pair<String, JsonObject>>()
    private val garden = GardenApi(ApiClient("https://maison.test", "t", HttpClient(MockEngine { request ->
        val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        sent += request.url.encodedPath to Json.parseToJsonElement(body) as JsonObject
        respond("""{"id":"c-1"}""", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
    }) { expectSuccess = false; install(ContentNegotiation) { json(MauriceJson) } }))

    @Test fun aChapterIsNamedByItsSlug() = runBlocking {
        assertEquals("c-1", garden.startBookConversation(156, "0016-Chapter Sixteen"))
        assertEquals("/api/conversations" to Json.parseToJsonElement("""{"book":156,"chapter":"0016-Chapter Sixteen"}"""), sent.single())
    }

    @Test fun aPassageTakesThePlaceOfTheChapter() = runBlocking {
        garden.startBookConversation(156, "0016-x", highlightId = 42)
        assertEquals(Json.parseToJsonElement("""{"book":156,"highlight":42}"""), sent.single().second)
    }

    @Test fun anArticlesPassageNamesItsEntry() = runBlocking {
        garden.startArticleConversation("fr", "un-article", 7)
        assertEquals(Json.parseToJsonElement("""{"entry":"articles/fr/un-article","article_highlight":7}"""), sent.single().second)
    }

    @Test fun aListedHighlightCarriesItsConversation() {
        val list = MauriceJson.decodeFromString(ListSerializer(Highlight.serializer()), """[
            {"id":1,"quote":"a","conversation_id":"c-9","conversation":{"id":"c-9","title":"Sur Stendhal","summary":"Ce qui en est ressorti."}},
            {"id":2,"quote":"b","conversation_id":"c-8","conversation":{"id":"c-8","title":null,"summary":null}},
            {"id":3,"quote":"c"}
        ]""")
        assertEquals(HighlightConversation("c-9", "Sur Stendhal", "Ce qui en est ressorti."), list[0].conversation)
        assertNull(list[1].conversation?.summary)
        assertNull(list[2].conversationId)
        assertNull(list[2].conversation)
    }
}
