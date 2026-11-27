package eu.chezmaurice.core

import eu.chezmaurice.core.carnet.Shelf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Both summaries travel with the list (`garden/entries?view=shelf`, carnet iOS
 * 12 November 2026): an article's page opens on them from the list, offline
 * too, since the list kept for offline is the server's text read again.
 */
class ShelfSummaryTest {
    private val text = """
        {"garden":{"username":"camille","site":null},
         "kinds":[{"kind":"articles","count":2}],
         "entries":[
          {"id":"articles/fr/sur-la-lecture","kind":"articles","collection":"articles","locale":"fr","slug":"sur-la-lecture","title":"Sur la lecture",
           "source":{"type":"article","captured":true,"word_count":2400,"reading_minutes":10,
                     "summary":{"text":"L'essentiel.","long":"Le résumé long.\n\nEn plusieurs paragraphes.","current":true}}},
          {"id":"articles/fr/sans-resume","kind":"articles","collection":"articles","locale":"fr","slug":"sans-resume","title":"Sans résumé",
           "source":{"type":"article","captured":true}}
         ]}
    """.trimIndent()

    @Test
    fun theListCarriesBothSummaries() {
        val entries = Shelf.parse(text).entries
        val summary = entries[0].source?.summary
        assertEquals("L'essentiel.", summary?.text)
        assertEquals("Le résumé long.\n\nEn plusieurs paragraphes.", summary?.long)
        assertTrue(summary?.current == true)
        assertNull(entries[1].source?.summary)
    }
}
