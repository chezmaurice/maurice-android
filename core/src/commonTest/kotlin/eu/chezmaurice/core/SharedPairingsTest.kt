package eu.chezmaurice.core

import eu.chezmaurice.core.model.Household
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.session.PairingsChannel
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.session.SharedPairings
import eu.chezmaurice.core.store.KeyValueStore
import eu.chezmaurice.core.store.MemoryKeyValueStore
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The households the Maurice app and Carnet share (SharedPairings.swift): one app's change is the other's. */
class SharedPairingsTest {
    /** What both apps reach: the last pairings written. */
    private class Shelf : PairingsChannel {
        var pairings: SharedPairings? = null
        var writes = 0
        override fun read() = pairings
        override fun write(pairings: SharedPairings) { this.pairings = pairings; writes++ }
    }

    private val alice = Member(id = "u1", displayName = "Alice", hasPin = true)
    private val bruno = Member(id = "u2", displayName = "Bruno")

    private fun paired(prefix: String, id: String, members: List<Member>, active: String?, tokens: Map<String, String>): KeyValueStore =
        MemoryKeyValueStore().apply {
            val h = Household(id = id, serverUrl = "https://tilleuls.test", name = "Les Tilleuls", members = members, activeMemberId = active, key = "K1")
            put("$prefix.households", MauriceJson.encodeToString(ListSerializer(Household.serializer()), listOf(h)))
            put("$prefix.currentHousehold", id)
            tokens.forEach { (m, t) -> putSecret("$prefix.token.$id.$m", t) }
        }

    @Test fun theFirstLaunchJoinsWhatEachAppHeld() {
        val shelf = Shelf()
        // The Maurice app knew Alice, Carnet Bruno, in the same household (local ids differ).
        Session(paired("maurice", "M-1", listOf(alice), "u1", mapOf("u1" to "tok-a")), "maurice", shared = shelf)
        val carnet = Session(paired("carnet", "C-9", listOf(bruno), "u2", mapOf("u2" to "tok-b")), "carnet", shared = shelf)
        val h = carnet.state.value.households.single()
        assertEquals("C-9", h.id, "the local id stays")
        assertEquals(listOf("u2", "u1"), h.members.map { it.id }, "what Carnet held first, what only Maurice knew added")
        assertEquals("u2", h.activeMemberId, "what Carnet held wins")
        assertTrue(carnet.hasToken("C-9", "u1"), "Alice's session reaches Carnet")
        assertTrue(carnet.hasToken("C-9", "u2"))
    }

    @Test fun signingOutInOneAppSignsOutInTheOther() {
        val shelf = Shelf()
        val maurice = Session(paired("maurice", "M-1", listOf(alice), "u1", mapOf("u1" to "tok-a")), "maurice", shared = shelf)
        val carnetStore = MemoryKeyValueStore()
        val carnet = Session(carnetStore, "carnet", shared = shelf)
        val h = carnet.state.value.households.single()
        assertEquals("https://tilleuls.test", h.serverUrl, "a household paired in Maurice appears in Carnet")
        assertEquals("tok-a", carnetStore.getSecret("carnet.token.${h.id}.u1"))

        maurice.disconnectMember("M-1", "u1")
        carnet.applyShared()
        assertNull(carnetStore.getSecret("carnet.token.${h.id}.u1"), "Alice's session is gone from Carnet too")
    }

    @Test fun forgettingAHouseholdForgetsItInBoth() {
        val shelf = Shelf()
        val maurice = Session(paired("maurice", "M-1", listOf(alice), "u1", mapOf("u1" to "tok-a")), "maurice", shared = shelf)
        val carnet = Session(MemoryKeyValueStore(), "carnet", shared = shelf)
        assertEquals(1, carnet.state.value.households.size)
        // As forgetting does: the household goes from the list.
        val writesBefore = shelf.writes
        shelf.pairings = shelf.pairings!!.copy(households = emptyList(), current = null)
        carnet.applyShared()
        assertTrue(carnet.state.value.households.isEmpty())
        assertNull(carnet.state.value.currentId)
        assertEquals(writesBefore, shelf.writes, "applying what the other app wrote never writes it back")
        maurice.applyShared()
        assertTrue(maurice.state.value.households.isEmpty())
    }

    @Test fun absorbKeepsWhatThisAppHolds() {
        val mine = SharedPairings(listOf(SharedPairings.Household(server = "https://a.test", key = null, name = "A", members = listOf(alice), active = "u1", tokens = mapOf("u1" to "mine"))), current = null)
        val theirs = SharedPairings(listOf(
            SharedPairings.Household(server = "https://moved.test", key = null, name = "A", members = listOf(alice, bruno), active = "u2", tokens = mapOf("u1" to "theirs", "u2" to "b")),
            SharedPairings.Household(server = "https://a.test", key = "K", name = "A'", members = listOf(bruno), active = "u2", tokens = mapOf("u1" to "theirs", "u2" to "b")),
        ), current = "https://a.test")
        val joined = mine.absorb(theirs)
        assertEquals(2, joined.households.size)
        val a = joined.households.first()
        assertEquals(mapOf("u1" to "mine", "u2" to "b"), a.tokens)
        assertEquals("K", a.key)
        assertEquals("u1", a.active)
        assertEquals("https://a.test", joined.current)
    }
}
