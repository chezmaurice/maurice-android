package eu.chezmaurice.core

import eu.chezmaurice.core.carnet.LinkTarget
import eu.chezmaurice.core.carnet.ResonanceSource
import eu.chezmaurice.core.carnet.ResonancesApi
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

/** Résonances (`/api/v1/garden/links`, data-api/routes/garden-links.ts). */
class ResonancesTest {
    private val sent = mutableListOf<String>()

    private fun api(answer: String) = ResonancesApi(ApiClient("https://maison.test", "t", HttpClient(MockEngine { request ->
        sent += "${request.method.value} ${request.url.encodedPathAndQuery} ${(request.body as? TextContent)?.text.orEmpty()}".trim()
        respond(answer, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }) {
        expectSuccess = false
        install(ContentNegotiation) { json(MauriceJson) }
    }))

    private val dune = LinkTarget("movies", "fr", "dune", "Dune", linkBasename = "dune-fiche", hasFiche = true)

    @Test fun theGardenIsSearchedAsTyped() = runTest {
        val targets = api("""{"targets":[{"collection":"movies","locale":"fr","slug":"dune","title":"Dune","date":"2026-10-01","link_basename":"dune-fiche","has_fiche":true}]}""")
            .targets("du ne")
        assertEquals("GET /api/v1/garden/links/targets?q=du%20ne", sent.single())
        assertEquals("dune-fiche", targets.single().linkBasename)
    }

    @Test fun aBookIsNamedByItsTitleAnArticleByItsFiche() = runTest {
        val api = api("{}")
        api.create(dune, "Le désert.", quote = "Un passage.", source = ResonanceSource("Les Fondations", bookTitle = "Les Fondations"))
        api.create(dune, "", quote = "Un passage.", source = ResonanceSource("Un article", basename = "un-article-fiche"))
        assertEquals(
            listOf(
                """POST /api/v1/garden/links {"to":{"locale":"fr","slug":"dune","collection":"movies"},"comment":"Le désert.","quote":"Un passage.","source_book_title":"Les Fondations"}""",
                """POST /api/v1/garden/links {"to":{"locale":"fr","slug":"dune","collection":"movies"},"quote":"Un passage.","source":{"label":"Un article","basename":"un-article-fiche"}}""",
            ),
            sent,
        )
    }
}
