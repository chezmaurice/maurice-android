package eu.chezmaurice.core

import eu.chezmaurice.core.carnet.SuggestionsApi
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a conversation is worth keeping (`/api/suggestions`, specs/carnet-suggestions.md). */
class SuggestionsTest {
    private val sent = mutableListOf<String>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(answer: String) = SuggestionsApi(ApiClient("https://maison.test", "t", HttpClient(MockEngine { request ->
        val body = (request.body as? TextContent)?.text.orEmpty()
        sent += "${request.method.value} ${request.url.encodedPathAndQuery} $body".trim()
        respond(answer, HttpStatusCode.OK, json)
    }) {
        expectSuccess = false
        install(ContentNegotiation) { json(MauriceJson) }
    }))

    @Test fun theDrawerReadsItsRows() = runTest {
        val list = api(
            """{"suggestions":[
                {"id":"s1","kind":"movies","title":"Dune","year":null,"subtitle":null,"image":null,"note":"Vu avec Nadia.","state":"pending","existing":false,
                 "candidates":[{"id":"tmdb:438631","title":"Dune","year":2021,"subtitle":"Denis Villeneuve","image":"/img/a.jpg"},{"id":"tmdb:841","title":"Dune","year":1984,"subtitle":"David Lynch","image":null}],
                 "entry":null,"web_path":null,"query":null,"searchable":true},
                {"id":"s2","kind":"books","title":"Le Rouge et le Noir","note":"","state":"kept","existing":true,"candidates":[],"entry":"books/fr/le-rouge-et-le-noir","web_path":"/camille/books/le-rouge-et-le-noir"}
            ],"pending":1,"kept":1}""",
        ).list("c 1")
        assertEquals("GET /api/suggestions?conversation=c%201", sent.single().substringBefore(" {"))
        assertEquals(2, list.suggestions.first().candidates.size)
        assertFalse(list.suggestions.first().isKept)
        assertTrue(list.suggestions.last().isKept)
        assertEquals("books/fr/le-rouge-et-le-noir", list.suggestions.last().entry)
        assertEquals(1, list.pending)
    }

    @Test fun aShowsEpisodesAreTickedAndKeptTogether() = runTest {
        val api = api(
            """{"feed":"920666","episodes":[
                {"id":"e1","title":"The Sunday Read","date":"2026-11-01T10:00:00Z","description":"A long read.","image":"https://img/e1.jpg","duration":1980},
                {"id":"e2","title":"Gaza, a year on","date":null,"description":"","image":"","duration":null}
            ],"suggested":"e2"}""",
        )
        val list = api.episodes("s3", feed = "920666")!!
        assertEquals("e2", list.suggested)
        assertEquals(1980, list.episodes.first().duration)
        // This mock answers every request with the episodes: only what is sent is checked here.
        runCatching { api.keep("s3", candidate = "pi:920666", episodes = listOf("e2", "e1")) }
        runCatching { api.list("c1", settle = true) }
        assertEquals(
            listOf(
                "GET /api/suggestions/s3/episodes?feed=920666",
                """POST /api/suggestions/s3/keep {"candidate":"pi:920666","episodes":["e2","e1"]}""",
                "GET /api/suggestions?conversation=c1&settle=1",
            ),
            sent,
        )
    }

    @Test fun keepingNamesThePickAndLookingAgainItsTerm() = runTest {
        val api = api("""{"id":"s1","kind":"movies","title":"Dune","state":"kept","found":3}""")
        api.keep("s1", candidate = "tmdb:841")
        api.search("s1", query = "Dune Lynch")
        api.search("s1", query = null)
        assertEquals(
            listOf(
                """POST /api/suggestions/s1/keep {"candidate":"tmdb:841","episodes":[]}""",
                """POST /api/suggestions/s1/search {"query":"Dune Lynch"}""",
                """POST /api/suggestions/s1/search {}""",
            ),
            sent,
        )
    }
}
