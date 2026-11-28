package eu.chezmaurice.core

import eu.chezmaurice.core.net.HouseholdImages
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The member's session goes with the household's own pictures, and nowhere else (28 November 2026). */
class HouseholdImagesTest {
    @AfterTest fun reset() { HouseholdImages.current = null }

    @Test fun onlyTheHouseholdsOwnOrigin() {
        assertTrue(HouseholdImages.sameOrigin("https://maison.test/api/images/a.jpg", "https://maison.test"))
        assertTrue(HouseholdImages.sameOrigin("https://MAISON.test:443/api/images/a.jpg", "https://maison.test"))
        assertFalse(HouseholdImages.sameOrigin("http://maison.test/api/images/a.jpg", "https://maison.test"), "another scheme")
        assertFalse(HouseholdImages.sameOrigin("https://maison.test:8443/api/images/a.jpg", "https://maison.test"), "another port")
        assertFalse(HouseholdImages.sameOrigin("https://cdn.example.com/cover.jpg", "https://maison.test"), "a provider's CDN")
        assertFalse(HouseholdImages.sameOrigin("https://maison.test.evil.example/a.jpg", "https://maison.test"))
        assertFalse(HouseholdImages.sameOrigin("not a url", "https://maison.test"))
    }

    @Test fun theSessionGoesWithTheHouseholdsPicturesOnly() {
        assertNull(HouseholdImages.authorization("https://maison.test/api/images/a.jpg"), "signed out")
        HouseholdImages.current = "https://maison.test" to "tok"
        assertEquals("Bearer tok", HouseholdImages.authorization("https://maison.test/api/images/a.jpg"))
        assertNull(HouseholdImages.authorization("https://cdn.example.com/cover.jpg"))
    }
}
