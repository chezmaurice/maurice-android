package eu.chezmaurice.core

import eu.chezmaurice.core.chat.ToolStep
import eu.chezmaurice.core.chat.TurnSources
import eu.chezmaurice.core.chat.TurnTally
import eu.chezmaurice.core.chat.candidateIdToken
import eu.chezmaurice.core.chat.cardKind
import eu.chezmaurice.core.chat.withoutReviewFooter
import eu.chezmaurice.core.model.DataBlock
import eu.chezmaurice.core.net.MauriceJson
import kotlin.test.Test
import kotlin.test.assertEquals

class CardsTest {
    private fun block(tool: String, json: String) = DataBlock(tool, MauriceJson.parseToJsonElement(json))

    private val web1 = block("web_search", """{"card":"sources","origin":"web","query":"vin sauge","count":12,"results":[
        {"title":"Accords","url":"https://www.example.com/accords/"},{"title":"Gnocchi","url":"http://cuisine.test/g"}]}""")
    private val web2 = block("web_search", """{"card":"sources","origin":"web","query":"vin beurre noisette","count":2,"results":[
        {"title":"Accords bis","url":"https://example.com/accords"},{"title":"Beurre","url":"https://beurre.test"}]}""")
    private val memory = block("corpus__search", """{"card":"sources","origin":"corpus","results":[{"title":"Recette","subtitle":"note","kind":"note"}]}""")

    @Test fun tallyFromBlocks() =
        assertEquals(TurnTally(web = 2, memory = 1, tools = 1), TurnTally.of(emptyList(), listOf(web1, web2, memory, block("garden__read_note", """{"x":1}"""))))

    @Test fun tallyFromStepsCountsOnlyWhatIsDone() =
        assertEquals(TurnTally(web = 1, memory = 0, tools = 2), TurnTally.of(
            listOf(ToolStep("web_search", 2, running = true), ToolStep("garden__read_note", 2, running = false)), emptyList(), doneOnly = true,
        ))

    @Test fun samePageTwiceIsOneSource() {
        val web = TurnSources.byOrigin(listOf(web1, memory, web2)).first { it.isWeb }
        assertEquals(listOf("Accords", "Gnocchi", "Beurre"), web.results.map { (it as kotlinx.serialization.json.JsonObject)["title"].toString().trim('"') })
        // 3 at hand, plus the 10 the first search found and did not carry.
        assertEquals(13, web.total)
        assertEquals(listOf("vin sauge", "vin beurre noisette"), web.queries)
    }

    @Test fun reviewFooterComesOff() {
        val review = block("people__review", """{"card":"review","footer":"À vérifier : Jeanne Martin."}""")
        assertEquals("review", review.cardKind)
        assertEquals("Voici le résumé.", withoutReviewFooter("Voici le résumé.\n\nÀ vérifier : Jeanne Martin.", listOf(review)))
    }

    @Test fun candidateTokens() {
        val row = MauriceJson.parseToJsonElement("""{"id":603,"title":"The Matrix"}""")
        assertEquals("tmdb 603", candidateIdToken("movies", row))
        assertEquals("wikidata Q42", candidateIdToken("people", MauriceJson.parseToJsonElement("""{"id":"Q42"}""")))
    }
}

class PartialMarkdownTest {
    private fun t(s: String) = eu.chezmaurice.core.chat.tidyPartialMarkdown(s)

    @Test fun anOpenItalicWaits() = assertEquals("Bonjour", t("Bonjour *(Echo mode — no"))
    @Test fun aClosedOneStays() = assertEquals("*(Echo mode.)* Vous", t("*(Echo mode.)* Vous"))
    @Test fun openBold() = assertEquals("Un", t("Un **soave"))
    @Test fun openCode() = assertEquals("Faites mousser", t("Faites mousser `80"))
    @Test fun snakeCaseIsNotEmphasis() = assertEquals("le fichier read_note est", t("le fichier read_note est"))
    @Test fun earlierLinesAreLeftAlone() = assertEquals("*a*\nb", t("*a*\nb *c"))
}
