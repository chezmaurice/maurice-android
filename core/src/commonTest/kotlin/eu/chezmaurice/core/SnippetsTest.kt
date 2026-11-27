package eu.chezmaurice.core

import eu.chezmaurice.core.chat.Snippet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnippetsTest {
    @Test fun fenceOutgrowsInnerFences() =
        assertEquals("````pasted\nvoir ```code```\n````", Snippet.Paste("voir ```code```").fenced)

    @Test fun articleHeaderThenText() = assertEquals(
        "```article\ntitle: Les gnocchi\nsite: Cuisine\nurl: https://cuisine.test/g\n\nPommes de terre, farine.\n```",
        Snippet.Article("Les gnocchi", site = "Cuisine", url = "https://cuisine.test/g", text = "Pommes de terre, farine.").fenced,
    )

    @Test fun linkOnly() = assertEquals("```article\ntitle: X\nurl: https://x.test\n```", Snippet.Article("X", url = "https://x.test").fenced)

    @Test fun cardsFirstThenTyped() = assertEquals(
        "```pasted\nA\n```\n\nEt toi ?",
        Snippet.compose("Et toi ?", listOf(Snippet.Paste("A"))),
    )

    @Test fun aLongPasteIsTakenOut() {
        val paste = "ligne\n".repeat(25)
        val (inserted, remaining) = Snippet.insertion("Avant  après", "Avant $paste après")!!
        assertEquals(paste, inserted)
        assertEquals("Avant  après", remaining)
    }

    @Test fun typingIsNot() = assertNull(Snippet.insertion("Bonjou", "Bonjour"))

    @Test fun source() = assertTrue(Snippet.Article("X", url = "https://www.lemonde.fr/a").source == "lemonde.fr")

    @Test fun readBack() {
        val sent = Snippet.compose("Tu en penses quoi ?", listOf(
            Snippet.Article("Example Domain", url = "https://example.com/", text = "This domain is for use in examples."),
            Snippet.Paste("ligne ```un```\nligne deux"),
        ))
        val (cards, typed) = Snippet.parse(sent)
        assertEquals("Tu en penses quoi ?", typed)
        assertEquals("Example Domain", (cards[0] as Snippet.Article).title)
        assertEquals("This domain is for use in examples.", (cards[0] as Snippet.Article).text)
        assertEquals("ligne ```un```\nligne deux", (cards[1] as Snippet.Paste).text)
    }

    @Test fun plainTextHasNoCards() = assertEquals(emptyList<Snippet>() to "Bonjour", Snippet.parse("Bonjour"))
}
