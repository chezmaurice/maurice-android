package eu.chezmaurice.core

import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.settings.ReportsApi
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
import kotlin.test.assertTrue

/** The admin's queue of reports (`/api/reports`, server/src/services/safety.ts). */
class ReportsTest {
    private val sent = mutableListOf<String>()

    private fun api(answer: String) = ReportsApi(ApiClient("https://maison.test", "t", HttpClient(MockEngine { request ->
        sent += "${request.method.value} ${request.url.encodedPathAndQuery} ${(request.body as? TextContent)?.text.orEmpty()}".trim()
        respond(answer, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }) {
        expectSuccess = false
        install(ContentNegotiation) { json(MauriceJson) }
    }))

    @Test fun aReplyOfMauricesComesWithItsQuestion() = runTest {
        val reports = api(
            """[
              {"id":"r1","room_id":"c1","target_type":"ai_reply","target_id":"m9","reason":"harmful_or_misleading","note":"Faux.","status":"open",
               "created_at":"2026-11-03 10:00:00","reporter_display_name":"Nadia","room_title":null,
               "reported_message":{"id":"m9","content":"La réponse.","author_id":null,"created_at":"2026-11-03 09:59:00"},"reported_member":null,
               "reported_question":"La question ?"},
              {"id":"r2","room_id":"c2","target_type":"member","target_id":"u3","reason":"child_safety","note":null,"status":"open",
               "created_at":"2026-11-03 09:00:00","reporter_display_name":"Camille","room_title":"Famille",
               "reported_message":null,"reported_member":{"id":"u3","display_name":"Tom","username":"tom"}}
            ]""",
        ).open()
        assertEquals("GET /api/reports?status=open", sent.single())
        assertTrue(reports.first().isAiReply)
        assertEquals("La question ?", reports.first().reportedQuestion)
        assertEquals("Tom", reports.last().reportedMember?.displayName)
    }

    @Test fun handledIsAnActionWithNeither() = runTest {
        val api = api("""{}""")
        api.act("r1", removeMessage = false, ejectMember = false)
        api.act("r2", removeMessage = true, ejectMember = false)
        api.dismiss("r3")
        assertEquals(
            listOf(
                """POST /api/reports/r1/action {"remove_message":false,"eject_member":false}""",
                """POST /api/reports/r2/action {"remove_message":true,"eject_member":false}""",
                """POST /api/reports/r3/dismiss {}""",
            ),
            sent,
        )
    }
}
