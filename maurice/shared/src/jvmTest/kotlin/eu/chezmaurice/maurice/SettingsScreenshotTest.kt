package eu.chezmaurice.maurice

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import eu.chezmaurice.core.model.Household
import eu.chezmaurice.core.model.Member
import eu.chezmaurice.core.model.Role
import eu.chezmaurice.core.net.MauriceJson
import eu.chezmaurice.core.session.Session
import eu.chezmaurice.core.store.MemoryKeyValueStore
import eu.chezmaurice.maurice.domains.LocalAccent
import eu.chezmaurice.maurice.settings.SettingsScreen
import eu.chezmaurice.ui.hexColor
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test

/** The settings' index, as an admin and as a guest. No server: the values stay blank. */
class SettingsScreenshotTest {
    private fun session(member: Member): Pair<Session, Household> {
        val household = Household("h1", "https://maison.test", "Famille Martin", "#2c5aa0", members = listOf(member), activeMemberId = member.id)
        val store = MemoryKeyValueStore()
        store.put("maurice.households", MauriceJson.encodeToString(ListSerializer(Household.serializer()), listOf(household)))
        store.put("maurice.currentHousehold", "h1")
        store.put("maurice.prefs.${member.id}.palette", "cream")
        return Session(store, "maurice") to household
    }

    @Test fun index() {
        for ((name, member) in listOf(
            "admin" to Member("u1", "Camille", "#3d6b4f", hasPin = true, role = Role.Admin, username = "camille"),
            "guest" to Member("u9", "Léa", "#7a4f6e", role = Role.Guest, username = "lea"),
        )) {
            val (session, household) = session(member)
            for (frame in listOf(Frames.tallPhone, Frames.desktop)) for (look in listOf(Looks.day, Looks.night)) {
                snapshot("settings-$name", frame, look) {
                    CompositionLocalProvider(LocalAccent provides hexColor(member.avatarColor)) {
                        Surface(Modifier.fillMaxSize()) { SettingsScreen(session, household, member) {} }
                    }
                }
            }
        }
    }
}

class InviteQrTest {
    @Test fun anInvitationLinkMakesAQrCode() {
        val m = eu.chezmaurice.maurice.platform.qrMatrix("https://maison.test/join?code=ABCD2345")
        kotlin.test.assertNotNull(m)
        kotlin.test.assertTrue(m.size >= 21 && m.all { it.size == m.size }, "a square of at least 21 modules")
        // The three finder patterns: dark corners.
        kotlin.test.assertTrue(m[0][0] && m[0][m.size - 1] && m[m.size - 1][0])
    }

    @Test fun sizesCountAsOnIos() {
        kotlin.test.assertEquals("444 KB", eu.chezmaurice.maurice.settings.formatBytesForTest(454_947))
        kotlin.test.assertEquals("48.8 KB", eu.chezmaurice.maurice.settings.formatBytesForTest(50_000))
        kotlin.test.assertEquals("512 B", eu.chezmaurice.maurice.settings.formatBytesForTest(512))
    }
}
