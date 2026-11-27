package eu.chezmaurice.core

import eu.chezmaurice.core.session.Invitation
import eu.chezmaurice.core.session.formatInviteCode
import eu.chezmaurice.core.session.typedInviteCode
import eu.chezmaurice.core.session.normalizeServerUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InvitationTest {
    @Test fun qrLink() = assertEquals(
        Invitation("https://maison.example.eu", "ABCD2345"),
        Invitation.parse("https://maison.example.eu/join?code=ABCD2345"),
    )

    @Test fun qrLinkKeepsPort() = assertEquals(
        Invitation("https://mac.local:3001", "ABCD2345"),
        Invitation.parse("https://mac.local:3001/join?code=ABCD2345"),
    )

    @Test fun appLink() = assertEquals(
        Invitation("https://maison.example.eu", "ABCD2345"),
        Invitation.parse("maurice://join?server=https%3A%2F%2Fmaison.example.eu%2F&code=ABCD2345"),
    )

    @Test fun carnetAppLink() = assertEquals(
        Invitation("https://maison.example.eu", "ABCD2345"),
        Invitation.parse("carnet://join?server=https%3A%2F%2Fmaison.example.eu&code=ABCD2345"),
    )

    @Test fun codeIsNormalised() = assertEquals(
        Invitation("https://nadia.chezmaurice.eu", "ABCD2345"),
        Invitation.parse("https://nadia.chezmaurice.eu/join?code=abcd-2345"),
    )

    @Test fun notAnInvitation() {
        assertNull(Invitation.parse("https://maison.example.eu"))
        assertNull(Invitation.parse("https://maison.example.eu/join"))
        assertNull(Invitation.parse("maison.example.eu"))
    }

    @Test fun addresses() {
        assertEquals("https://mac.local:3001", normalizeServerUrl(" mac.local:3001/ "))
        assertEquals("http://192.168.1.2:3001", normalizeServerUrl("http://192.168.1.2:3001"))
        assertNull(normalizeServerUrl("   "))
    }

    @Test fun codes() = assertEquals("ABCD-2345", formatInviteCode("abcd2345"))

    @Test fun typedCodes() {
        assertEquals("ABC", typedInviteCode("abc"))
        assertEquals("ABCD", typedInviteCode("abcd"))
        // The dash, typed, stays; a fifth character brings it anyway.
        assertEquals("ABCD-", typedInviteCode("abcd-"))
        assertEquals("ABCD-E", typedInviteCode("abcde"))
        assertEquals("ABCD-E", typedInviteCode("abcd-e"))
        // A backspace over the dash removes it.
        assertEquals("ABCD", typedInviteCode("ABCD"))
        // Pasted, spaced or too long: eight letters or digits.
        assertEquals("ABCD-2345", typedInviteCode(" abcd 2345 "))
        assertEquals("ABCD-2345", typedInviteCode("ABCD-23456"))
        assertEquals("", typedInviteCode("-"))
    }
}

class QueryEncodingTest {
    @Test fun onlyUnreservedStayBare() = kotlin.test.assertEquals(
        "%2Fg%2Fnadia%2Ffr%2Fnotes%2Fgnocchi-%C3%A0-la-sauge",
        eu.chezmaurice.core.session.encodeQueryValue("/g/nadia/fr/notes/gnocchi-à-la-sauge"),
    )
}

class PhotoSizeTest {
    @Test fun aLargePhotoFitsBothLimits() {
        val (w, h) = eu.chezmaurice.core.media.PhotoSize.target(4032, 3024)!!
        kotlin.test.assertTrue(maxOf(w, h) <= 1568)
        kotlin.test.assertTrue(eu.chezmaurice.core.media.PhotoSize.visualTokens(w, h) <= 1568)
    }

    @Test fun theRoundingTrap() {
        // 1280×960 is under the edge limit but bills 46×35 = 1610 tokens.
        val (w, h) = eu.chezmaurice.core.media.PhotoSize.target(1280, 960)!!
        kotlin.test.assertTrue(eu.chezmaurice.core.media.PhotoSize.visualTokens(w, h) <= 1568)
    }

    @Test fun aSmallPhotoIsLeftAlone() = kotlin.test.assertNull(eu.chezmaurice.core.media.PhotoSize.target(800, 600))
}
