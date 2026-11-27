package eu.chezmaurice.carnet

import eu.chezmaurice.carnet.entry.cardCanBeShared
import eu.chezmaurice.carnet.entry.cardShareText
import eu.chezmaurice.carnet.entry.plainCardText
import eu.chezmaurice.carnet.entry.posterFileName
import eu.chezmaurice.core.carnet.EntryDetail
import eu.chezmaurice.core.carnet.ShelfEntry
import eu.chezmaurice.core.carnet.ShelfSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a shared card says (EntryShareCard.swift): the shared face in plain words, never the fiche. */
class ShareCardTest {
    private val film = ShelfEntry("movies/fr/sans-soleil", "movies", "movies", "fr", "sans-soleil", "Sans soleil", byline = "Chris Marker")
    private val article = ShelfEntry("posts/en/x", "posts", "posts", "en", "x", "The quiet web", byline = null,
        source = ShelfSource(type = "article", url = "https://example.org/quiet-web"))

    @Test fun theCardInPlainWords() {
        val card = EntryDetail.Shared(
            state = "draft", title = "Sans soleil",
            body = "# Sans soleil\n\nUn film qui **pense** à voix haute, comme [[la-jetee|La Jetée]] ou [[Tokyo]].\nVoir [la critique](https://x.test/c) et __le reste__.",
        )
        assertEquals(
            "Sans soleil — Chris Marker\n\nUn film qui pense à voix haute, comme La Jetée ou Tokyo.\nVoir la critique et le reste.",
            cardShareText(film, card),
        )
    }

    @Test fun theLink() {
        val published = EntryDetail.Shared(state = "published", publicUrl = "https://camille.chezmaurice.eu/fr/films/sans-soleil", body = "Beau.")
        assertTrue(cardShareText(film, published).endsWith("\n\nhttps://camille.chezmaurice.eu/fr/films/sans-soleil"))
        // A draft: the original's link, never the garden's.
        val draft = EntryDetail.Shared(state = "draft", publicUrl = "https://camille.chezmaurice.eu/x", body = "Calme.")
        assertEquals("The quiet web\n\nCalme.\n\nhttps://example.org/quiet-web", cardShareText(article, draft))
        assertEquals("Sans soleil — Chris Marker\n\nCalme.", cardShareText(film, draft))
    }

    @Test fun whenTheCardCanBeShared() {
        assertFalse(cardCanBeShared(null, hasSite = false))
        assertFalse(cardCanBeShared(EntryDetail.Shared(title = "Sans soleil", body = "# Sans soleil\n", state = "published"), hasSite = true), "empty")
        // With a site: published only.
        assertTrue(cardCanBeShared(EntryDetail.Shared(body = "Quelques mots.", state = "published"), hasSite = true))
        assertFalse(cardCanBeShared(EntryDetail.Shared(body = "Quelques mots.", state = "draft"), hasSite = true))
        // Without one: a draft that says something.
        assertTrue(cardCanBeShared(EntryDetail.Shared(body = "Quelques mots.", state = "draft"), hasSite = false))
    }

    @Test fun details() {
        assertEquals("a b", plainCardText("  [[x|a]] [[b]]  "))
        assertEquals("sans-soleil.jpg", posterFileName(film, "image/jpeg"))
        assertEquals("sans-soleil.png", posterFileName(film, "image/png"))
    }
}
