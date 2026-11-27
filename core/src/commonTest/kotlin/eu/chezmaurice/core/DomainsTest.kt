package eu.chezmaurice.core

import eu.chezmaurice.core.composer.ItemType
import eu.chezmaurice.core.composer.SearchEntry
import eu.chezmaurice.core.composer.TrayItem
import eu.chezmaurice.core.domains.DomainStore
import eu.chezmaurice.core.domains.DomainsApi
import eu.chezmaurice.core.domains.SaveOutcome
import eu.chezmaurice.core.model.ProposalDecision
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.MauriceJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DomainsTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        ApiClient("https://maison.test", "t", HttpClient(MockEngine(handler)) {
            expectSuccess = false
            install(ContentNegotiation) { json(MauriceJson) }
        })

    @Test fun aFollowedBookIsAlwaysSummaries() {
        val book = TrayItem(ItemType.Book, "42", "Le Guépard", representation = "full", scopeMode = "progress")
        val p = book.payload()
        assertEquals("summary", (p["representation"] as JsonPrimitive).content)
        assertEquals(42, (p["id"] as JsonPrimitive).content.toInt())
        assertEquals("progress", (p["scope"]!!.jsonObject["mode"] as JsonPrimitive).content)
    }

    @Test fun aSavedItemComesBackWithItsSnapshot() {
        val spec = MauriceJson.parseToJsonElement(
            """{"type":"note","id":"jardin","recurse":true,"include_archived":false,"exclude":["vieux"],"snapshot":{"weight":1200,"count":4,"encrypted":true}}""",
        ).jsonObject
        val item = TrayItem.fromSpec(spec)!!
        assertEquals(listOf("vieux"), item.exclude)
        assertTrue(item.recurse && item.encrypted)
        assertEquals(1200, item.weight)
        assertEquals(spec.filterKeys { it != "snapshot" }, item.payload())
    }

    @Test fun aNewItemTakesItsDefaults() {
        assertTrue(TrayItem.from(SearchEntry(ItemType.Note, "moc", "MOC", moc = true)).recurse)
        assertTrue(TrayItem.from(SearchEntry(ItemType.Folder, "f1", "Factures")).recurse)
        assertTrue(TrayItem.from(SearchEntry(ItemType.File, "x", "scan.pdf", kind = "pdf")).na)
    }

    @Test fun aTakenNameSaysWhichDomainHasIt() = runTest {
        val api = DomainsApi(client {
            respond("""{"error":"domain_name_taken","existing":{"id":"d1","name":"Le Jardin"}}""", HttpStatusCode.Conflict, json)
        })
        val outcome = api.save(null, JsonObject(mapOf("name" to JsonPrimitive("le jardin"))))
        assertEquals(SaveOutcome.NameTaken("Le Jardin"), outcome)
    }

    @Test fun anAdoptionBringsTheNewDomainIn() = runTest {
        var applied: String? = null
        var rows = "[]"
        val http = client { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Post && path == "/api/domains/proposals/apply" -> {
                    applied = (request.body as TextContent).text
                    rows = """[{"id":"d9","name":"Le potager","created_by":"u2"}]"""
                    respond("""{"adopted":[{"id":"p1","name":"Le potager","domain_id":"d9"}],"errors":[]}""", HttpStatusCode.OK, json)
                }
                path == "/api/domains/proposals" -> respond("""{"total_conversations":10,"proposals":[],"settled":[],"unseen":0}""", HttpStatusCode.OK, json)
                path == "/api/maurices" -> respond(rows, HttpStatusCode.OK, json)
                path == "/api/domains" -> respond("""{"domains":[],"companions":[]}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        val store = DomainStore(DomainsApi(http), CoroutineScope(Dispatchers.Default))
        val outcome = store.apply(listOf(ProposalDecision("p1", "adopt", seed = true)))
        assertEquals("d9", outcome!!.adopted.single().domainId)
        assertEquals(listOf("Le potager"), store.state.value.domains.map { it.name })
        val sent = MauriceJson.parseToJsonElement(applied!!).jsonObject["items"].toString()
        assertTrue("\"seed\":true" in sent && "\"action\":\"adopt\"" in sent, sent)
        assertIs<JsonObject>(MauriceJson.parseToJsonElement(applied!!))
    }
}
