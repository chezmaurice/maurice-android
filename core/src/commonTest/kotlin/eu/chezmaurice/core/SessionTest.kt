package eu.chezmaurice.core

import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.session.EnrollOutcome
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.store.MemoryKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** An open invitation: profile first, then a PIN, then signed in. */
    @Test fun openInvitationToSignedIn() = runTest {
        val bodies = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            when (request.url.encodedPath) {
                "/api/health" -> respond("""{"status":"ok","setup_complete":true,"household":"Chez nous","household_color":"#a6452e"}""", HttpStatusCode.OK, json)
                "/api/auth/enroll" -> { bodies += body
                    if ("display_name" in body) respond("""{"user_id":"u9","token":"tok9","role":"standard","needs_pin":true}""", HttpStatusCode.OK, json)
                    else respond("""{"needs_profile":true,"household":"Chez nous"}""", HttpStatusCode.OK, json)
                }
                "/api/users/me" -> respond("""{"id":"u9","username":"odile","display_name":"Odile","avatar_color":"#2c5aa0","has_pin":false,"role":"standard"}""", HttpStatusCode.OK, json)
                "/api/auth/set-pin" -> { bodies += body; respond("""{"ok":true}""", HttpStatusCode.OK, json) }
                "/api/users" -> respond("""[{"id":"u1","display_name":"Camille","avatar_color":"#000000","has_pin":true},{"id":"u9","display_name":"Odile","avatar_color":"#2c5aa0","has_pin":true}]""", HttpStatusCode.OK, json)
                else -> respond("""{"error":"Not found"}""", HttpStatusCode.NotFound, json)
            }
        }) { expectSuccess = false; install(ContentNegotiation) { json(MauriceJson) }; install(HttpTimeout) }

        val store = MemoryKeyValueStore()
        val session = Session(store, "maurice", http)
        val household = session.connect("maison.test")
        assertEquals("https://maison.test", household.serverUrl)
        assertEquals("Chez nous", household.name)

        assertIs<EnrollOutcome.NeedsProfile>(session.enroll("abcd-2345"))
        val pin = assertIs<EnrollOutcome.NeedsPin>(session.enroll("ABCD2345", "Odile", "#2c5aa0"))
        assertNull(session.state.value.activeMember, "nobody is stored before the PIN")
        // The app is killed here; a new process picks the enrolment up again.
        val resumed = Session(store, "maurice", http).pendingEnrollment(household.id)
        assertEquals(pin.pending, resumed)
        session.setPin(resumed!!, "1234")
        assertNull(session.pendingEnrollment(household.id))

        val member = session.state.value.activeMember!!
        assertEquals("u9", member.id)
        assertEquals("odile", member.username, "the roster has no usernames; /me's is kept")
        assertTrue(member.hasPin)
        assertEquals(2, session.state.value.current!!.members.size)
        assertTrue(bodies.first().contains("\"code\":\"abcd-2345\""))
        assertEquals("""{"pin":"1234"}""", bodies.last())

        // A fresh Session over the same store finds everything again.
        val again = Session(store, "maurice", http)
        assertEquals("u9", again.state.value.activeMember?.id)
        assertTrue(again.hasToken(household.id, "u9"))
    }
}
