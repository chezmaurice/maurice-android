package eu.chezmaurice.core

import eu.chezmaurice.core.carnet.GardenApi
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.MauriceJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A saved article filed under a domain (`/api/v1/garden/articles`, server/src/services/domainArticles.ts). */
class ArticleDomainTest {
    private val sent = mutableListOf<String>()

    private fun api(answer: String) = GardenApi(ApiClient("https://maison.test", "t", HttpClient(MockEngine { request ->
        sent += "${request.method.value} ${request.url.encodedPath} ${(request.body as? TextContent)?.text.orEmpty()}".trim()
        respond(answer, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }) {
        expectSuccess = false
        install(ContentNegotiation) { json(MauriceJson) }
    }))

    @Test fun theSaveSaysWhereItWentAndWhatElseThereIs() = runTest {
        val saved = api(
            """{"slug":"le-sommeil","locale":"fr","title":"Le sommeil","duplicate":false,"needs_capture":false,"tags":[],
               "domain":{"id":"d1","name":"Santé","icon":"heart"},"domain_by":"auto",
               "domain_choices":[{"id":"d2","name":"Jardin","icon":null},{"id":"d1","name":"Santé","icon":"heart"}]}""",
        ).saveArticle("https://lemonde.fr/x", "fr", "Pour mes horaires", null)
        assertEquals("Santé", saved.domain?.name)
        assertEquals(listOf("Jardin", "Santé"), saved.domainChoices.map { it.name })
        assertEquals("le-sommeil", saved.slug)
    }

    @Test fun anOlderServerAnswersNoDomain() = runTest {
        val saved = api("""{"slug":"x","title":"X","duplicate":true}""").saveArticle("https://a.b/x", "fr", null, null)
        assertNull(saved.domain)
        assertTrue(saved.domainChoices.isEmpty())
        assertTrue(saved.duplicate)
    }

    @Test fun noneIsSentAsNull() = runTest {
        val now = api("""{"domain":null,"by":"none","choices":[]}""").setArticleDomain("le sommeil", "fr", null)
        assertNull(now)
        assertEquals("""PUT /api/v1/garden/articles/le%20sommeil/domain {"domain_id":null,"locale":"fr"}""", sent.single())
    }
}
