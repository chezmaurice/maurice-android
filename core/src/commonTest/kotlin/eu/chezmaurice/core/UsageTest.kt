package eu.chezmaurice.core

import eu.chezmaurice.core.chat.ConversationUsage
import eu.chezmaurice.core.chat.TurnEvent
import eu.chezmaurice.core.chat.UsageFormat
import eu.chezmaurice.core.chat.cacheHitRate
import eu.chezmaurice.core.chat.promptTokens
import eu.chezmaurice.core.chat.saved
import eu.chezmaurice.core.model.Message
import eu.chezmaurice.core.model.TurnUsage
import eu.chezmaurice.core.net.MauriceJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** What a reply cost: decoded as the server sends it, summed and spelled as iOS does. */
class UsageTest {
    private val usageJson =
        """{"provider":"anthropic","model":"claude-sonnet","rounds":2,"input":1200,"output":350,"cache_read":8000,"cache_write":800,"cost":0.0123,"cost_uncached":0.031}"""

    @Test fun usageEventDecodes() {
        val event = assertIs<TurnEvent.Usage>(TurnEvent.parse("""{"type":"usage","usage":$usageJson}"""))
        assertEquals(TurnUsage("anthropic", "claude-sonnet", 2, 1200, 350, 8000, 800, 0.0123, 0.031), event.usage)
    }

    @Test fun messageCarriesItsUsage() {
        val m = MauriceJson.decodeFromString(Message.serializer(),
            """{"id":"m1","role":"assistant","content":"Bonjour","usage":$usageJson,"created_at":"2026-11-21 10:00:00"}""")
        assertEquals(10_000, m.usage!!.promptTokens)
        assertEquals(0.8, m.usage!!.cacheHitRate!!, 1e-9)
        assertEquals(0.0187, m.usage!!.saved!!, 1e-9)
    }

    @Test fun unpricedUsageAndNoUsage() {
        val m = MauriceJson.decodeFromString(Message.serializer(),
            """{"id":"m1","role":"assistant","content":"x","usage":{"provider":"scaleway","model":"m","rounds":1,"input":10,"output":5,"cache_read":0,"cache_write":0,"cost":null,"cost_uncached":null}}""")
        assertNull(m.usage!!.cost)
        assertNull(m.usage!!.saved)
        assertNull(MauriceJson.decodeFromString(Message.serializer(), """{"id":"m2","role":"user","content":"y"}""").usage)
    }

    @Test fun conversationSumsOnlyWhatIsMetered() {
        val a = TurnUsage("anthropic", "sonnet", 2, 100, 50, 900, 0, 0.02, 0.05)
        val b = TurnUsage("ollama", "llama", 1, 300, 20, 0, 0, null, null)
        val c = TurnUsage("anthropic", "sonnet", 1, 100, 30, 0, 900, 0.04, 0.03)
        val u = ConversationUsage.of(listOf(a, null, b, c))
        assertEquals(3, u.turns)
        assertEquals(1, u.unmetered)
        assertEquals(4, u.rounds)
        assertEquals(500, u.input)
        assertEquals(100, u.output)
        assertEquals(2300, u.promptTokens)
        assertEquals(2400, u.totalTokens)
        assertEquals(2, u.pricedTurns)
        assertEquals(0.06, u.cost!!, 1e-9)
        assertEquals(0.08, u.costUncached!!, 1e-9)
        assertEquals(0.02, u.saved!!, 1e-9)
        assertEquals(0.03, u.averageCost!!, 1e-9)
        assertEquals(0.04, u.costliestTurn!!, 1e-9)
        assertEquals(900.0 / 2300, u.cacheHitRate!!, 1e-9)
        assertEquals(listOf("sonnet" to 2, "llama" to 1), u.models.map { it.model to it.turns })
        assertEquals(0.06, u.models[0].cost!!, 1e-9)
        assertNull(u.models[1].cost)
    }

    @Test fun nothingMetered() {
        val u = ConversationUsage.of(listOf(null, null))
        assertEquals(0, u.turns)
        assertEquals(2, u.unmetered)
        assertNull(u.cost)
        assertNull(u.averageCost)
        assertNull(u.cacheHitRate)
    }

    @Test fun moneyAsEachLanguageWritesIt() {
        assertEquals("€0.42", UsageFormat.money(0.42, "en"))
        assertEquals("0,42${UsageFormat.NBSP}€", UsageFormat.money(0.42, "fr"))
        assertEquals("€${UsageFormat.NBSP}0,42", UsageFormat.money(0.42, "nl"))
        // Under a cent: four decimals, never "0,00 €".
        assertEquals("€0.0042", UsageFormat.money(0.0042, "en"))
        assertEquals("1,25${UsageFormat.NBSP}€", UsageFormat.money(1.25, "de"))
        // A local model is free.
        assertEquals("€0", UsageFormat.money(0.0, "en"))
        assertEquals("1,25${UsageFormat.NBSP}€", UsageFormat.money(1.25, "fr"))
    }

    @Test fun tokensAndPercent() {
        assertEquals("850", UsageFormat.tokens(850))
        assertEquals("12.3k", UsageFormat.tokens(12_345))
        assertEquals("1.0k", UsageFormat.tokens(1000))
        assertEquals("1.25M", UsageFormat.tokens(1_250_000))
        assertEquals("82%", UsageFormat.percent(0.8249))
        assertEquals("100%", UsageFormat.percent(1.0))
    }

    @Test fun theCoin() {
        val priced = TurnUsage("anthropic", "sonnet", 1, 200, 100, 800, 0, 0.0123, 0.02)
        assertEquals("€0.01", UsageFormat.headline(priced, "en"))
        assertEquals("80% cached", UsageFormat.cacheLabel(priced))
        val unpriced = TurnUsage("x", "m", 1, 1500, 300, 0, 0, null, null)
        assertEquals("1.8k tok", UsageFormat.headline(unpriced, "fr"))
        assertEquals("no cache", UsageFormat.cacheLabel(unpriced))
        assertNull(UsageFormat.cacheLabel(TurnUsage(output = 3)))
    }
}
