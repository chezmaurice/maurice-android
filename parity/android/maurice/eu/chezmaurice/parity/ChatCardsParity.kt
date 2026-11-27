package eu.chezmaurice.parity

import eu.chezmaurice.core.chat.ToolStep
import eu.chezmaurice.core.chat.TurnSources
import eu.chezmaurice.core.chat.TurnTally
import eu.chezmaurice.core.chat.str
import eu.chezmaurice.core.chat.withoutReviewFooter
import eu.chezmaurice.core.model.parseServerDate
import eu.chezmaurice.maurice.chat.tally
import eu.chezmaurice.parity.Parity.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.test.Test

/** The tally under a reply, its sources, the review footer, the server's dates (chat-cards.json). */
class ChatCardsParity {
    @Test fun chatCards() = Parity.check("maurice", "chat-cards") { c ->
        val blocks = ChatSupport.blocks(c.array("blocks"))
        when (c.string("op")) {
            "tally" -> {
                val steps = c.array("steps").orEmpty().map { it as JsonObject }.map {
                    ToolStep(it["tool"].str() ?: "", (it["count"] as? JsonPrimitive)?.intOrNull ?: 1,
                        (it["running"] as? JsonPrimitive)?.booleanOrNull ?: false)
                }
                val t = TurnTally.of(steps, blocks, doneOnly = c.bool("doneOnly") ?: false)
                mapOf("web" to t.web, "memory" to t.memory, "tools" to t.tools, "empty" to t.isEmpty, "text" to ChatSupport.strings.tally(t))
            }
            "sources" -> TurnSources.byOrigin(blocks).map { g ->
                mapOf("origin" to g.origin, "results" to g.results.map { mapOf("title" to it.str("title"), "url" to it.str("url")) })
            }
            "footer" -> mapOf("shown" to withoutReviewFooter(c.string("content").orEmpty(), blocks))
            "date" -> mapOf("date" to ChatSupport.iso(parseServerDate(c.string("text"))))
            else -> mapOf("unknown op" to c.string("op"))
        }
    }
}
