package eu.chezmaurice.core

import eu.chezmaurice.core.session.Invitation
import eu.chezmaurice.core.signup.SignupEnd
import eu.chezmaurice.core.signup.SignupPoll
import eu.chezmaurice.core.signup.SignupRefusal
import eu.chezmaurice.core.signup.SignupTower
import eu.chezmaurice.core.signup.followSignup
import eu.chezmaurice.core.store.MemoryKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** « Créer mon Maurice »: the tower's form and the wait (`SignupViews.swift`, cc5b99d). */
class SignupTest {
    private val sent = mutableListOf<String>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** The tower answers each request in turn from [answers]: (status, body), or a thrown error for null. */
    private fun tower(vararg answers: Pair<Int, String>?): SignupTower {
        val queue = ArrayDeque(answers.toList())
        return SignupTower(HttpClient(MockEngine { request ->
            val body = (request.body as? TextContent)?.text.orEmpty()
            sent += "${request.method.value} ${request.url.encodedPathAndQuery} ${request.headers["Accept-Language"].orEmpty()} $body".trim()
            val (status, text) = queue.removeFirst() ?: throw IllegalStateException("offline")
            respond(text, HttpStatusCode.fromValue(status), json)
        }), "https://account.chezmaurice.eu/", language = "fr")
    }

    @Test fun theZoneIsTheTowersParent() {
        assertEquals("chezmaurice.eu", tower().zone)
        assertEquals("localhost", SignupTower(HttpClient(MockEngine { respond("") }), "http://localhost:8080").zone)
    }

    @Test fun theAddressIsAskedAbout() = runTest {
        val check = tower(200 to """{"ok":false,"name":"martin","reason":"taken"}""").checkName("martin")
        assertEquals("GET /api/names/martin fr", sent.single())
        assertEquals("taken", check.reason)
    }

    @Test fun suggestionsAreTheFreeOnesFourAtMost() = runTest {
        val names = tower(200 to """{"suggestions":[{"name":"jean","ok":false},{"name":"jean1","ok":true},{"name":"chez-jean","ok":true},{"name":"jean-m","ok":true},{"name":"jeanne","ok":true},{"name":"jj","ok":true}]}""")
            .suggest("Jean Marc")
        assertEquals("GET /api/suggest?q=Jean%20Marc fr", sent.single())
        assertEquals(listOf("jean1", "chez-jean", "jean-m", "jeanne"), names)
    }

    @Test fun theFormIsSentAndAnswersTheSecret() = runTest {
        val poll = tower(200 to """{"poll":"p-123"}""").signup("martin", "Martin", "m@example.org", "BETA")
        assertEquals("""POST /api/app/signup fr {"name":"martin","owner_name":"Martin","email":"m@example.org","code":"BETA","terms":"yes","via":"app"}""", sent.single())
        assertEquals("p-123", poll)
    }

    @Test fun aRefusalCarriesItsCodeAndSentence() = runTest {
        val e = assertFailsWith<SignupRefusal> {
            tower(409 to """{"error":"taken","message":"Cette adresse est prise."}""").signup("martin", "Martin", "m@example.org", "")
        }
        assertEquals("taken", e.code); assertEquals("Cette adresse est prise.", e.said)
        val bare = assertFailsWith<SignupRefusal> { tower(502 to "<html>").signup("x", "X", "x@y", "") }
        assertEquals("http_502", bare.code); assertNull(bare.said)
    }

    @Test fun theWaitEndsWithTheHouseholdToJoin() = runTest {
        val states = mutableListOf<String>()
        val end = followSignup(
            tower(
                200 to """{"state":"unverified"}""",
                null,
                200 to """{"state":"waiting"}""",
                200 to """{"state":"live","server":null,"invite_code":"ABCD2345"}""",
                200 to """{"state":"live","server":"martin.chezmaurice.eu/","invite_code":"ABCD2345"}""",
            ),
            "p/1", onState = { states += it }, pause = {},
        )
        assertEquals(SignupEnd.Live(Invitation("https://martin.chezmaurice.eu", "ABCD2345")), end)
        assertEquals(listOf("unverified", "waiting", "live", "live"), states)
        assertEquals("GET /api/app/signup/p%2F1 fr", sent.first())
    }

    @Test fun anUnknownOrFailedSignupIsLost() = runTest {
        assertEquals(SignupEnd.Lost, followSignup(tower(503 to "", 404 to """{"error":"not_found"}"""), "p", pause = {}))
        assertEquals(SignupEnd.Lost, followSignup(tower(200 to """{"state":"failed"}"""), "p", pause = {}))
        assertEquals(SignupEnd.Lost, followSignup(tower(200 to """{"state":"closed"}"""), "p", pause = {}))
    }

    @Test fun theSecretIsKeptAcrossLaunches() {
        val store = MemoryKeyValueStore()
        assertNull(SignupPoll.saved(store))
        SignupPoll.save(store, "p-123")
        assertEquals("p-123", SignupPoll.saved(store))
        SignupPoll.save(store, null)
        assertNull(SignupPoll.saved(store))
    }
}
