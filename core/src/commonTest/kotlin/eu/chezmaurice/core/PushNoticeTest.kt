package eu.chezmaurice.core

import eu.chezmaurice.core.push.PushNotice
import eu.chezmaurice.core.push.PushTap
import eu.chezmaurice.core.push.householdToSwitchTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A household's push as UnifiedPush hands it decrypted (server
 * `services/push.ts`): what it says, what its tap opens, in which household.
 */
class PushNoticeTest {
    @Test fun aConversationMauriceOpened() {
        val n = PushNotice.parse("""{"title":"Le marché","body":"Maurice: Voici la liste","conversationId":"c-42","householdTag":"h1"}""")!!
        assertEquals("Le marché", n.title)
        assertEquals("Maurice: Voici la liste", n.body)
        assertEquals("h1", n.householdTag)
        assertEquals(PushTap.Conversation("c-42"), n.tap)
    }

    @Test fun anEntrySharedWithTheMember() {
        val n = PushNotice.parse("""{"title":"Walden","body":"Camille l’a partagé avec vous","entry":"books/fr/walden","householdTag":"h2"}""")!!
        assertEquals(PushTap.Entry("books/fr/walden"), n.tap)
        assertEquals("h2", n.householdTag)
    }

    @Test fun aConversationWinsOverAnEntry() {
        // iOS reads `conversationId` first.
        val n = PushNotice.parse("""{"title":"t","body":"b","conversationId":"c","entry":"notes/en/x"}""")!!
        assertEquals(PushTap.Conversation("c"), n.tap)
    }

    @Test fun anAccountEventOpensNothingInParticular() {
        val n = PushNotice.parse("""{"title":"A new device is in your account","body":"It came in with a code.","householdTag":"h1"}""")!!
        assertNull(n.tap)
        assertEquals("h1", n.householdTag)
    }

    @Test fun aMalformedEntryIsNotOpened() {
        for (ref in listOf("books/walden", "books/fr/walden/extra", "books//walden", "")) {
            assertNull(PushNotice.parse("""{"title":"t","body":"b","entry":"$ref"}""")!!.tap, ref)
        }
    }

    @Test fun missingOrOddFieldsAreTolerated() {
        val n = PushNotice.parse("""{"body":42,"conversationId":null,"householdTag":"","extra":{"x":1}}""")!!
        assertNull(n.title)
        assertEquals("", n.body)
        assertNull(n.tap)
        assertNull(n.householdTag)
    }

    @Test fun notJsonIsNoNotice() {
        assertNull(PushNotice.parse("New message"))
        assertNull(PushNotice.parse("[1,2]"))
        assertNull(PushNotice.parse(""))
    }

    @Test fun theTapSwitchesToAPairedHouseholdOnly() {
        val paired = listOf("h1", "h2")
        assertEquals("h2", householdToSwitchTo("h2", paired, currentId = "h1"))
        assertNull(householdToSwitchTo("h1", paired, currentId = "h1"))   // already on screen
        assertNull(householdToSwitchTo("h9", paired, currentId = "h1"))   // not paired here (forgotten since)
        assertNull(householdToSwitchTo(null, paired, currentId = "h1"))
    }
}
