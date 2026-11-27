package eu.chezmaurice.core

import eu.chezmaurice.core.net.ApiError
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.session.EnrollOutcome
import eu.chezmaurice.core.session.Invitation
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.session.sameCode
import eu.chezmaurice.core.signup.SignupEnd
import eu.chezmaurice.core.signup.SignupTower
import eu.chezmaurice.core.signup.accessCode
import eu.chezmaurice.core.signup.followSignup
import eu.chezmaurice.core.signup.hostedName
import eu.chezmaurice.core.signup.isNoHost
import eu.chezmaurice.core.signup.towerZone
import eu.chezmaurice.core.store.MemoryKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The access-code way in (iOS 2a703c9, 20 November 2026): a sign-up link
 * opens the sign-up, a hosted address is asked of the tower first, an
 * unknown host is waited for, a received invitation is kept until it serves.
 */
class AccessCodeTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun signupLinksCarryTheAccessCode() {
        assertEquals("BETA-42", accessCode("https://account.chezmaurice.eu/?code=BETA-42"))
        assertEquals("BETA-42", accessCode("  https://ACCOUNT.chezmaurice.eu?code=BETA-42 "))
        assertEquals("BETA-42", accessCode("maurice://signup?code=BETA-42"))
        // A household's invitation, another host, another scheme, no code: none.
        assertNull(accessCode("https://odile.chezmaurice.eu/join?code=ABCD2345"))
        assertNull(accessCode("https://example.org/?code=BETA-42"))
        assertNull(accessCode("maurice://join?server=x&code=ABCD2345"))
        assertNull(accessCode("carnet://signup?code=BETA-42"))
        assertNull(accessCode("https://account.chezmaurice.eu/"))
        assertNull(accessCode("BETA-42"))
        // Another tower, as a build may be pointed at.
        assertEquals("X", accessCode("http://localhost:8080/?code=X", "http://localhost:8080"))
    }

    @Test fun hostedNames() {
        assertEquals("chezmaurice.eu", towerZone())
        assertEquals("odile", hostedName("odile.chezmaurice.eu"))
        assertEquals("odile", hostedName(" https://Odile.chezmaurice.eu/ "))
        assertEquals("odile", hostedName("odile"))
        assertNull(hostedName("maison.example.org"))
        assertNull(hostedName("mac.local:3001"))
        assertNull(hostedName("localhost"))
        assertNull(hostedName(""))
    }

    @Test fun noSuchHost() {
        class UnknownHostException(message: String) : Exception(message)
        assertTrue(isNoHost(UnknownHostException("odile.chezmaurice.eu")))
        assertTrue(isNoHost(RuntimeException("wrapped", UnknownHostException("x"))))
        assertTrue(isNoHost(Exception("Unable to resolve host \"odile.chezmaurice.eu\": No address associated with hostname")))
        assertFalse(isNoHost(Exception("Connection refused")))
    }

    @Test fun sameCodeHoweverTyped() {
        assertTrue(sameCode("abcd-2345", "ABCD2345"))
        assertFalse(sameCode("abcd-2345", null))
        assertFalse(sameCode("", ""))
    }

    /** Made, the tower says; joined only once it answers from here. */
    @Test fun theWaitEndsWhenTheHouseholdAnswers() = runTest {
        var asked = 0
        val tower = SignupTower(HttpClient(MockEngine {
            respond("""{"state":"live","server":"https://odile.chezmaurice.eu","invite_code":"ABCD2345"}""", HttpStatusCode.OK, json)
        }))
        var unanswered = 0
        val end = followSignup(tower, "p", answers = { asked++; asked >= 3 }, onUnanswered = { unanswered++ }, pause = {})
        assertEquals(SignupEnd.Live(Invitation("https://odile.chezmaurice.eu", "ABCD2345")), end)
        assertEquals(2, unanswered)
    }

    private fun household(enroll: () -> Pair<HttpStatusCode, String>) = HttpClient(MockEngine { request ->
        when (request.url.encodedPath) {
            "/api/health" -> respond("""{"status":"ok","household":"Chez nous"}""", HttpStatusCode.OK, json)
            "/api/auth/enroll" -> enroll().let { (status, body) -> respond(body, status, json) }
            "/api/users/me" -> respond("""{"id":"u9","display_name":"Odile","avatar_color":"#2c5aa0","has_pin":false,"role":"standard"}""", HttpStatusCode.OK, json)
            else -> respond("""[]""", HttpStatusCode.OK, json)
        }
    }) { expectSuccess = false; install(ContentNegotiation) { json(MauriceJson) }; install(HttpTimeout) }

    @Test fun anInvitationIsKeptUntilItServes() = runTest {
        val http = household { HttpStatusCode.OK to """{"needs_profile":true,"household":"Chez nous"}""" }
        val store = MemoryKeyValueStore()
        val session = Session(store, "maurice", http)
        session.receive(Invitation("https://maison.test", "ABCD2345"))
        assertEquals("ABCD2345", session.keptInvite)
        // "Who's joining?" asked, then closed: the invitation stays, also after a relaunch.
        assertIs<EnrollOutcome.NeedsProfile>(session.enroll("ABCD2345"))
        assertEquals("ABCD2345", Session(store, "maurice", http).keptInvite)
    }

    @Test fun anInvitationThatLetSomeoneInIsForgotten() = runTest {
        val http = household { HttpStatusCode.OK to """{"user_id":"u9","token":"tok9","role":"standard","needs_pin":true}""" }
        val session = Session(MemoryKeyValueStore(), "maurice", http)
        session.receive(Invitation("https://maison.test", "ABCD2345"))
        assertIs<EnrollOutcome.NeedsPin>(session.enroll("ABCD-2345", "Odile", "#2c5aa0"))
        assertNull(session.keptInvite)
    }

    @Test fun aRefusedInvitationIsForgotten() = runTest {
        val http = household { HttpStatusCode.Unauthorized to """{"error":"Invalid or expired code"}""" }
        val session = Session(MemoryKeyValueStore(), "maurice", http)
        session.receive(Invitation("https://maison.test", "ABCD2345"))
        // Another code refused: the kept one stays.
        assertFailsWith<ApiError> { session.enroll("ZZZZ9999") }
        assertEquals("ABCD2345", session.keptInvite)
        assertFailsWith<ApiError> { session.enroll("abcd2345") }
        assertNull(session.keptInvite)
    }
}
