package eu.chezmaurice.core

import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.settings.DeviceCodePurpose
import eu.chezmaurice.core.settings.SettingsApi
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
import kotlin.test.assertNull

/** The register of interventions and the admin's "Why this code?" (maurice 0252ce8). */
class InterventionsTest {
    private val sent = mutableListOf<String>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(answer: String, status: HttpStatusCode = HttpStatusCode.OK) = SettingsApi(ApiClient("https://maison.test", "t", HttpClient(MockEngine { request ->
        val body = (request.body as? TextContent)?.text.orEmpty()
        sent += "${request.method.value} ${request.url.encodedPathAndQuery} $body".trim()
        respond(answer, status, json)
    }) {
        expectSuccess = false
        install(ContentNegotiation) { json(MauriceJson) }
    }))

    @Test fun theRegisterIsAskedInItsSecondVersionWithItsReasons() = runTest {
        val events = api("""{"events":[
            {"id":4,"kind":"operator_logs","created_at":"2026-11-22 09:12:00","seen_at":null,"reason":"Ticket 112: replies cut short"},
            {"id":3,"kind":"device_at_request","created_at":"2026-11-21 18:00:00","seen_at":"2026-11-21 19:00:00","reason":null},
            {"id":2,"kind":"something_new","created_at":"2026-11-20 08:00:00","extra":true},
            {"id":1,"kind":"household_export","created_at":"2026-11-19 08:00:00","seen_at":null}
        ]}""").accountEvents()!!
        assertEquals("GET /api/me/events?v=2", sent.single())
        assertEquals(listOf("operator_logs", "device_at_request", "something_new", "household_export"), events.map { it.kind })
        assertEquals("Ticket 112: replies cut short", events[0].reason)
        assertNull(events[1].reason)
        assertNull(events[2].seenAt)
        assertEquals(3, events.count { it.seenAt == null })
    }

    @Test fun aServerWithoutTheRegisterSaysNothing() = runTest {
        assertNull(api("""{"error":"not found"}""", HttpStatusCode.NotFound).accountEvents())
    }

    @Test fun readingTheRegisterIsToldInItsSecondVersion() = runTest {
        api("""{"ok":true}""").accountEventsSeen()
        assertEquals("POST /api/me/events/seen?v=2 {}", sent.single())
    }

    @Test fun aCodeAskedForByTheMemberSaysSo() = runTest {
        val code = api("""{"code":"ABCD2345","expires_at":"2026-11-29 10:00:00"}""", HttpStatusCode.Created)
            .newDeviceCode("u2", DeviceCodePurpose.MemberRequest)
        assertEquals("""POST /api/users/u2/invite {"purpose":"member_request"}""", sent.single())
        assertEquals("ABCD2345", code.code)
    }

    @Test fun theAdminGoingInGivesTheReason() = runTest {
        api("""{"code":"ABCD2345","expires_at":"2026-11-29 10:00:00"}""", HttpStatusCode.Created)
            .newDeviceCode("u2", DeviceCodePurpose.AdminEntry, "  Her phone was lost  ")
        assertEquals("""POST /api/users/u2/invite {"purpose":"admin_entry","reason":"Her phone was lost"}""", sent.single())
    }

    @Test fun theAdminsOwnAccountAsksNothing() = runTest {
        api("""{"code":"ABCD2345","expires_at":"2026-11-29 10:00:00"}""", HttpStatusCode.Created).newDeviceCode("u1")
        assertEquals("POST /api/users/u1/invite {}", sent.single())
    }

    @Test fun aMissingReasonIsRefusedWithItsCode() = runTest {
        val e = assertFailsWith<ApiError> {
            api("""{"error":"Say why you are going into this account","code":"reason_required"}""", HttpStatusCode.BadRequest)
                .newDeviceCode("u2", DeviceCodePurpose.AdminEntry, " ")
        }
        assertEquals("""POST /api/users/u2/invite {"purpose":"admin_entry"}""", sent.single())
        assertEquals("reason_required", e.code)
    }

    @Test fun theCurrentCodeIsReadFirst() = runTest {
        assertNull(api("""{"invite":null}""").currentDeviceCode("u2"))
        assertEquals("GET /api/users/u2/invite", sent.single())
    }
}
