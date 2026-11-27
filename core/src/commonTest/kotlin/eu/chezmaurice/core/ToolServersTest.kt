package eu.chezmaurice.core

import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.settings.ToolServersApi
import eu.chezmaurice.core.settings.toolServerAddress
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The member's own MCP servers (`/api/tool-servers`, server/src/routes/toolServers.ts). */
class ToolServersTest {
    private val sent = mutableListOf<String>()

    private fun api(answer: String, status: HttpStatusCode = HttpStatusCode.OK) = ToolServersApi(ApiClient("https://maison.test", "t", HttpClient(MockEngine { request ->
        sent += "${request.method.value} ${request.url.encodedPathAndQuery} ${(request.body as? TextContent)?.text.orEmpty()}".trim()
        respond(answer, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }) {
        expectSuccess = false
        install(ContentNegotiation) { json(MauriceJson) }
    }))

    private val row = """{"id":"s1","name":"Linear","prefix":"x_linear","url":"https://mcp.linear.app/mcp","auth_header":null,"auth":"oauth",
        "has_token":true,"enabled":true,"state":"ok","last_error":null,"checked_at":"2026-11-20 10:00:00","created_at":"2026-11-20 09:59:00",
        "tools":[{"name":"list_issues","description":"Issues."},{"name":"create_issue","description":""}],"dropped":2}"""

    @Test fun theListReadsAsTheServerWritesIt() = runTest {
        val list = api("""{"servers":[$row],"allowed":true,"limits":{"servers":8,"tools_per_server":40}}""").list()!!
        assertEquals("GET /api/tool-servers", sent.single())
        val s = list.servers.single()
        assertTrue(list.allowed)
        assertTrue(s.signsIn)
        assertEquals("mcp.linear.app", s.host)
        assertEquals(listOf("list_issues", "create_issue"), s.tools.map { it.name })
        assertEquals(2, s.dropped)
    }

    @Test fun aGuestMayNotAdd() = runTest {
        val list = api("""{"servers":[],"allowed":false}""").list()!!
        assertFalse(list.allowed)
        assertTrue(list.servers.isEmpty())
    }

    @Test fun anUnreadableListIsNull() = runTest {
        assertNull(api("""{"error":"boom"}""", HttpStatusCode.InternalServerError).list())
    }

    @Test fun addingSendsNoEmptyToken() = runTest {
        val api = api(row)
        api.add("Linear", "https://mcp.linear.app/mcp", null)
        api.add("Tâches", "https://t.example/mcp", "sk-1")
        assertEquals(
            listOf(
                """POST /api/tool-servers {"name":"Linear","url":"https://mcp.linear.app/mcp"}""",
                """POST /api/tool-servers {"name":"Tâches","url":"https://t.example/mcp","token":"sk-1"}""",
            ),
            sent,
        )
    }

    @Test fun aServerThatWantsASignInAnswers428() = runTest {
        val e = assertFailsWith<ApiError> { api("""{"error":"this server asks you to sign in"}""", HttpStatusCode(428, "Precondition Required")).add("L", "https://l.example", null) }
        assertEquals(428, e.status)
    }

    @Test fun theSignInSaysWhereToGo() = runTest {
        val api = api("""{"authorize_url":"https://auth.example/authorize?state=abc"}""")
        assertEquals("https://auth.example/authorize?state=abc", api.signIn("Linear", "https://mcp.linear.app/mcp"))
        assertEquals("https://auth.example/authorize?state=abc", api.signInAgain("s1"))
        assertEquals(
            listOf(
                """POST /api/tool-servers/oauth/start {"name":"Linear","url":"https://mcp.linear.app/mcp"}""",
                """POST /api/tool-servers/oauth/start {"id":"s1"}""",
            ),
            sent,
        )
    }

    @Test fun switchingTokenRefreshAndRemoval() = runTest {
        val api = api(row)
        assertEquals("s1", api.setEnabled("s1", false).id)
        api.setToken("s1", "sk-2")
        api.refresh("s1")
        api.remove("s1")
        assertEquals(
            listOf(
                """PATCH /api/tool-servers/s1 {"enabled":false}""",
                """PATCH /api/tool-servers/s1 {"token":"sk-2"}""",
                """POST /api/tool-servers/s1/refresh {}""",
                "DELETE /api/tool-servers/s1",
            ),
            sent,
        )
    }

    @Test fun anAddressWithoutSchemeIsHttps() {
        assertEquals("https://mcp.example.com/mcp", toolServerAddress("  mcp.example.com/mcp "))
        assertEquals("http://box.local/mcp", toolServerAddress("http://box.local/mcp"))
    }
}
