package eu.chezmaurice.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.chezmaurice.core.session.Ed25519Reference
import eu.chezmaurice.core.session.ed25519Verify
import eu.chezmaurice.core.session.txtRecords
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HouseholdMove on the phone itself: the system's resolver (DnsResolver)
 * against a real TXT record, and Ed25519 — the platform's from Android 13,
 * the reference code below. Needs the network.
 */
@RunWith(AndroidJUnit4::class)
class HouseholdMoveDeviceTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun aRealTxtRecordIsRead() = runBlocking {
        assertEquals(listOf("v=DMARC1; p=quarantine"), txtRecords("_dmarc.chezmaurice.eu"))
        assertEquals(emptyList(), txtRecords("_maurice.does-not-exist.chezmaurice.eu"))
    }

    /** RFC 8032 §7.1, test 1. */
    @Test fun ed25519() {
        val public = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val signature = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertTrue(ed25519Verify(public, ByteArray(0), signature))
        assertTrue(Ed25519Reference.verify(public, ByteArray(0), signature))
        assertFalse(ed25519Verify(public, byteArrayOf(1), signature))
    }
}
