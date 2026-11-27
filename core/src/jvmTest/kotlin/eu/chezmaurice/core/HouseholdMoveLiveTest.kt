package eu.chezmaurice.core

import eu.chezmaurice.core.model.Health
import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.session.HouseholdMove
import eu.chezmaurice.core.session.txtRecords
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HouseholdMove against the real world. Needs the network: skipped unless
 * MAURICE_LIVE_TESTS=1. MAURICE_LIVE_HOUSEHOLD (a household's address, a
 * server of 5 November 2026 or later) adds its proof, checked both ways.
 */
class HouseholdMoveLiveTest {
    private val live = System.getenv("MAURICE_LIVE_TESTS") == "1"

    /** The desktop's lookup, through the system's resolver. */
    @Test fun aRealTxtRecordIsRead() = runBlocking {
        if (!live) return@runBlocking
        val found = txtRecords("_dmarc.chezmaurice.eu")
        println("live: _dmarc.chezmaurice.eu → $found")
        assertEquals(listOf("v=DMARC1; p=quarantine"), found)
        assertEquals(emptyList(), txtRecords("_maurice.does-not-exist.chezmaurice.eu"))
    }

    /** A real server's signature: its own key is accepted, any other refused. */
    @Test fun aRealHouseholdProvesItsKey() = runBlocking {
        val url = System.getenv("MAURICE_LIVE_HOUSEHOLD")?.takeIf { live } ?: return@runBlocking
        val http = ApiClient.defaultHttpClient()
        val key = MauriceJson.decodeFromString(Health.serializer(), http.get("$url/api/health").bodyAsText()).identityKey!!
        println("live: $url holds $key")
        assertTrue(HouseholdMove.proves(url, key, http))
        val other = (if (key.first() == 'A') "B" else "A") + key.drop(1)
        assertFalse(HouseholdMove.proves(url, other, http))
    }
}
