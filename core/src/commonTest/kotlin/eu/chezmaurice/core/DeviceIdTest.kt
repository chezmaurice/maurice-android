package eu.chezmaurice.core

import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.session.Invitation
import eu.chezmaurice.core.session.PairingsChannel
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.session.SharedPairings
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the installation at chez-margot taught (21–22 November 2026): one
 * device id for the phone, sent with every sign-in and the same in both
 * apps; an invitation that has just served is not used a second time.
 */
class DeviceIdTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Shelf : PairingsChannel {
        var pairings: SharedPairings? = null
        override fun read() = pairings
        override fun write(pairings: SharedPairings) { this.pairings = pairings }
    }

    private fun household(bodies: MutableList<String>) = HttpClient(MockEngine { request ->
        val body = request.body.toByteArray().decodeToString()
        when (request.url.encodedPath) {
            "/api/health" -> respond("""{"status":"ok","household":"Chez Margot"}""", HttpStatusCode.OK, json)
            "/api/auth/enroll" -> { bodies += body; respond("""{"user_id":"u9","token":"tok9","role":"standard","needs_pin":false}""", HttpStatusCode.OK, json) }
            "/api/auth/login" -> { bodies += body; respond("""{"token":"tok9","user":{"id":"u9","display_name":"Margot","avatar_color":"#2c5aa0","has_pin":true,"role":"standard"}}""", HttpStatusCode.OK, json) }
            "/api/users/me" -> respond("""{"id":"u9","display_name":"Margot","avatar_color":"#2c5aa0","has_pin":false,"role":"standard"}""", HttpStatusCode.OK, json)
            else -> respond("""[]""", HttpStatusCode.OK, json)
        }
    }) { expectSuccess = false; install(ContentNegotiation) { json(MauriceJson) }; install(HttpTimeout) }

    @Test fun theDeviceIdGoesWithEverySignIn() = runTest {
        val bodies = mutableListOf<String>()
        val session = Session(MemoryKeyValueStore(), "maurice", household(bodies))
        session.connect("margot.test")
        session.enroll("ABCD2345")
        val id = session.deviceId
        assertTrue(id.isNotBlank())
        assertEquals(id, session.deviceId, "stable")
        assertTrue(bodies.single().contains("\"device_id\":\"$id\""), bodies.single())
    }

    @Test fun bothAppsSettleOnOneId() {
        val shelf = Shelf()
        val mauriceStore = MemoryKeyValueStore().apply { put("device.id", "bbbb") }
        val carnetStore = MemoryKeyValueStore().apply { put("device.id", "aaaa") }
        val maurice = Session(mauriceStore, "maurice", shared = shelf)
        val carnet = Session(carnetStore, "carnet", shared = shelf)
        maurice.applyShared()
        carnet.applyShared()
        assertEquals("aaaa", maurice.deviceId)
        assertEquals("aaaa", carnet.deviceId)
        // A phone where only one app ran: the other takes its id.
        val shelf2 = Shelf()
        val first = Session(MemoryKeyValueStore(), "maurice", shared = shelf2)
        val second = Session(MemoryKeyValueStore(), "carnet", shared = shelf2)
        first.applyShared(); second.applyShared()
        assertEquals(first.deviceId, second.deviceId)
    }

    @Test fun anInvitationThatJustServedIsNotUsedAgain() = runTest {
        var clock = 1_000_000L
        val bodies = mutableListOf<String>()
        val session = Session(MemoryKeyValueStore(), "maurice", household(bodies), now = { clock })
        val invitation = Invitation("https://margot.test", "ABCD2345")
        session.receive(invitation)
        session.enroll(invitation.code)
        assertNull(session.keptInvite)
        // The same invitation handed over again a moment later: kept aside.
        clock += 100
        assertTrue(session.justServed("https://margot.test", "abcd-2345"))
        session.receive(invitation)
        assertNull(session.keptInvite, "not offered again")
        assertFalse(session.justServed("https://autre.test", "ABCD2345"), "another household")
        // A minute later it is an invitation like any other.
        clock += 60_000
        assertFalse(session.justServed("https://margot.test", "ABCD2345"))
        session.receive(invitation)
        assertEquals("ABCD2345", session.keptInvite)
    }
}
