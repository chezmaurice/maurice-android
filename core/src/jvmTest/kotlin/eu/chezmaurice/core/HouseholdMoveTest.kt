package eu.chezmaurice.core

import eu.chezmaurice.core.model.Household
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.session.Ed25519Reference
import eu.chezmaurice.core.session.HouseholdMove
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.session.ed25519Verify
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Following a household that moved (HouseholdMove.swift; docs/moving-a-household.md in maurice). */
class HouseholdMoveTest {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun rawPublic(pair: KeyPair) = pair.public.encoded.takeLast(32).toByteArray()
    private fun sign(pair: KeyPair, message: ByteArray) =
        Signature.getInstance("Ed25519").run { initSign(pair.private); update(message); sign() }
    private fun keys() = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    // ── The record ─────────────────────────────────────────

    @Test fun aForwardingRecordIsRead() {
        assertEquals("https://foyer.dupont.eu", HouseholdMove.parse("v=maurice1; moved=https://foyer.dupont.eu"))
        assertEquals("https://foyer.dupont.eu", HouseholdMove.parse("moved=https://foyer.dupont.eu/ ;V=maurice1"))
        assertNull(HouseholdMove.parse("moved=https://foyer.dupont.eu"), "v is required")
        assertNull(HouseholdMove.parse("v=maurice2; moved=https://foyer.dupont.eu"))
        assertNull(HouseholdMove.parse("v=maurice1; moved=http://foyer.dupont.eu"), "https only")
        assertNull(HouseholdMove.parse("v=spf1 include:_spf.google.com ~all"))
    }

    @Test fun aTxtRecordsStringsAreJoined() {
        val rdata = byteArrayOf(11) + "v=maurice1;".encodeToByteArray() + byteArrayOf(26) + " moved=https://foyer.test/".encodeToByteArray()
        assertEquals("v=maurice1; moved=https://foyer.test/", HouseholdMove.decodeTxt(rdata))
    }

    /** What Android's resolver answers: a whole DNS message, the answer's name compressed. */
    @Test fun aRawDnsAnswerIsRead() {
        val name = byteArrayOf(8) + "_maurice".encodeToByteArray() + byteArrayOf(6) + "maison".encodeToByteArray() + byteArrayOf(4) + "test".encodeToByteArray() + byteArrayOf(0)
        val text = "v=maurice1; moved=https://foyer.test"
        val rdata = byteArrayOf(text.length.toByte()) + text.encodeToByteArray()
        val header = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 2, 0, 0, 0, 0)
        val question = name + byteArrayOf(0, 16, 0, 1)
        fun answer(type: Int, data: ByteArray) = byteArrayOf(0xc0.toByte(), 12, 0, type.toByte(), 0, 1, 0, 0, 0x0e, 0x10, 0, data.size.toByte()) + data
        val message = header + question + answer(5, byteArrayOf(0)) + answer(16, rdata)
        assertEquals(listOf(text), HouseholdMove.txtOfResponse(message))
        val nxdomain = message.copyOf().also { it[3] = 0x83.toByte() }
        assertEquals(emptyList(), HouseholdMove.txtOfResponse(nxdomain))
        assertEquals(emptyList(), HouseholdMove.txtOfResponse(byteArrayOf(1, 2, 3)))
    }

    // ── The signature ──────────────────────────────────────

    /** RFC 8032 §7.1, test 1, through both the platform and the reference code. */
    @Test fun rfc8032() {
        val public = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val signature = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertTrue(ed25519Verify(public, ByteArray(0), signature))
        assertTrue(Ed25519Reference.verify(public, ByteArray(0), signature))
        assertFalse(Ed25519Reference.verify(public, byteArrayOf(0), signature))
    }

    /** The reference code (Android 10 to 12) agrees with the platform's, both ways. */
    @Test fun theReferenceAgreesWithThePlatform() {
        repeat(20) { i ->
            val pair = keys()
            val message = "maurice-household-proof:v2\nnonce-$i\nhttps://foyer.test".encodeToByteArray()
            val signature = sign(pair, message)
            assertTrue(Ed25519Reference.verify(rawPublic(pair), message, signature))
            val bent = signature.copyOf().also { it[i % 64] = (it[i % 64].toInt() xor 1).toByte() }
            assertFalse(Ed25519Reference.verify(rawPublic(pair), message, bent))
            assertFalse(Ed25519Reference.verify(rawPublic(keys()), message, signature))
        }
    }

    // ── Following ──────────────────────────────────────────

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** What a household at [origin] answers to `?proof=<nonce>`, signing as [signer] and claiming [claimedKey]. */
    private fun health(signer: KeyPair, claimedKey: String, origin: String, nonce: String?): String {
        val proof = nonce?.let { b64.encodeToString(sign(signer, "maurice-household-proof:v2\n$it\n$origin".encodeToByteArray())) }
        val extra = proof?.let { ""","proof":"$it","proof_origin":"$origin"""" } ?: ""
        return """{"status":"ok","household":"Chez nous","identity_key":"$claimedKey"$extra}"""
    }

    /**
     * maison.test is down; foyer.test answers as [signer], claiming [claimedKey];
     * relais.test asks foyer.test and passes its answer on, [relayed] as it sees fit.
     */
    private fun network(signer: KeyPair, claimedKey: String, relayed: (String) -> String = { it }) = HttpClient(MockEngine { request ->
        val nonce = request.url.parameters["proof"]
        when (request.url.host) {
            "maison.test" -> throw java.io.IOException("down")
            "foyer.test" -> respond(health(signer, claimedKey, "https://foyer.test", nonce), HttpStatusCode.OK, json)
            "relais.test" -> respond(relayed(health(signer, claimedKey, "https://foyer.test", nonce)), HttpStatusCode.OK, json)
            else -> throw java.io.IOException("no such host")
        }
    }) { expectSuccess = false; install(ContentNegotiation) { json(MauriceJson) }; install(HttpTimeout) }

    private fun pairedStore(key: String?): MemoryKeyValueStore {
        val household = Household("h1", "https://maison.test", "Maison", members = listOf(Member("u1", "Camille", "#3d6b4f")), activeMemberId = "u1", key = key)
        return MemoryKeyValueStore().apply {
            put("maurice.households", MauriceJson.encodeToString(ListSerializer(Household.serializer()), listOf(household)))
            put("maurice.currentHousehold", "h1")
            putSecret("maurice.token.h1.u1", "tok")
            put("maurice.connect_disclosure.maison.test", "1")
        }
    }

    private val record: suspend (String) -> List<String> = { name ->
        if (name == "_maurice.maison.test") listOf("v=maurice1; moved=https://foyer.test") else emptyList()
    }

    @Test fun aHouseholdThatProvesItsKeyIsFollowed() = runBlocking {
        val pair = keys()
        val key = b64.encodeToString(rawPublic(pair))
        val store = pairedStore(key)
        val session = Session(store, "maurice", network(pair, key), txtLookup = record)
        session.refreshHouseholds()
        val h = session.state.value.households.single()
        assertEquals("https://foyer.test", h.serverUrl)
        assertEquals("h1", h.id)
        assertEquals("Chez nous", h.name)
        assertEquals(key, h.key)
        assertTrue(session.hasToken("h1", "u1"), "the member's session stays")
        assertEquals("1", store.get("maurice.connect_disclosure.foyer.test"))
    }

    @Test fun anotherKeyIsNotFollowed() = runBlocking {
        val pinned = b64.encodeToString(rawPublic(keys()))
        val impostor = keys()
        // Claims the pinned key, cannot sign for it.
        val session = Session(pairedStore(pinned), "maurice", network(impostor, pinned), txtLookup = record)
        session.refreshHouseholds()
        assertEquals("https://maison.test", session.state.value.households.single().serverUrl)
        // Signs, but with its own key.
        val own = b64.encodeToString(rawPublic(impostor))
        val again = Session(pairedStore(pinned), "maurice", network(impostor, own), txtLookup = record)
        again.refreshHouseholds()
        assertEquals("https://maison.test", again.state.value.households.single().serverUrl)
    }

    /** A record that points at a server which only passes the question on to the real household. */
    @Test fun aRelayedProofIsNotFollowed() = runBlocking {
        val pair = keys()
        val key = b64.encodeToString(rawPublic(pair))
        val toTheRelay: suspend (String) -> List<String> = { name ->
            if (name == "_maurice.maison.test") listOf("v=maurice1; moved=https://relais.test") else emptyList()
        }
        val rewrites = listOf<(String) -> String>(
            { it }, // the household's answer, as is
            { it.replace("https://foyer.test", "https://relais.test") }, // naming itself: the signature is for another address
            { it.replace(Regex(""","proof_origin":"[^"]*""""), "") }, // naming no address
        )
        for (rewrite in rewrites) {
            val session = Session(pairedStore(key), "maurice", network(pair, key, rewrite), txtLookup = toTheRelay)
            session.refreshHouseholds()
            assertEquals("https://maison.test", session.state.value.households.single().serverUrl)
        }
        // The same household, asked at its own address, is followed (aHouseholdThatProvesItsKeyIsFollowed).
        assertTrue(HouseholdMove.proves("https://foyer.test", key, network(pair, key)))
        assertFalse(HouseholdMove.proves("https://relais.test", key, network(pair, key)))
    }

    @Test fun theAddressSignedIsAnOrigin() {
        assertEquals("https://foyer.test", HouseholdMove.origin("https://Foyer.Test:443/x"))
        assertEquals("https://foyer.test:8443", HouseholdMove.origin("https://foyer.test:8443"))
        assertEquals("http://127.0.0.1:4711", HouseholdMove.origin("http://127.0.0.1:4711/"))
        assertNull(HouseholdMove.origin("https://a@foyer.test"))
        assertNull(HouseholdMove.origin("ftp://foyer.test"))
        assertNull(HouseholdMove.origin("foyer.test"))
    }

    @Test fun withoutAPinnedKeyNothingIsFollowed() = runBlocking {
        val pair = keys()
        val session = Session(pairedStore(null), "maurice", network(pair, b64.encodeToString(rawPublic(pair))), txtLookup = record)
        session.refreshHouseholds()
        assertEquals("https://maison.test", session.state.value.households.single().serverUrl)
    }

    /** Pinned once, never replaced. */
    @Test fun theKeyIsPinnedOnce() = runBlocking {
        val http = HttpClient(MockEngine {
            respond("""{"status":"ok","household":"Maison","identity_key":"second"}""", HttpStatusCode.OK, json)
        }) { expectSuccess = false; install(ContentNegotiation) { json(MauriceJson) } }
        val unpinned = Session(pairedStore(null), "maurice", http)
        unpinned.refreshHouseholds()
        assertEquals("second", unpinned.state.value.households.single().key)
        val pinned = Session(pairedStore("first"), "maurice", http)
        pinned.refreshHouseholds()
        assertEquals("first", pinned.state.value.households.single().key)
    }
}
