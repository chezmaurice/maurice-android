package eu.chezmaurice.core.chat

import eu.chezmaurice.core.model.TurnUsage
import eu.chezmaurice.core.text.printfFixed
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// What a reply cost (iOS `TurnUsage`, `ConversationUsage`, `UsageFormat` in
// APIClient.swift). The server persists `usage` on each assistant message and
// streams it once as a `usage` event just before `done`; `cost` and
// `cost_uncached` are in euros at the price the household pays, null when the
// model has no price on file (0 for a local model, which is free).

/** Every token the prompt side of this turn touched, cached or not. */
val TurnUsage.promptTokens: Int get() = input + cacheRead + cacheWrite

/** Share of the prompt served from cache, 0…1; null when there was no prompt. */
val TurnUsage.cacheHitRate: Double? get() = if (promptTokens > 0) cacheRead.toDouble() / promptTokens else null

/** Euros the cache saved on this turn, when both figures are known. */
val TurnUsage.saved: Double? get() {
    val c = cost ?: return null
    val u = costUncached ?: return null
    return maxOf(0.0, u - c)
}

/**
 * Every metered turn of a conversation folded into one figure per column,
 * from the usage on the assistant messages on screen. Turns with no usage
 * are counted in [unmetered] and left out of the sums rather than rounding
 * the total down to "cheap".
 */
data class ConversationUsage(
    val turns: Int,
    val unmetered: Int,
    val rounds: Int,
    val input: Int,
    val output: Int,
    val cacheRead: Int,
    val cacheWrite: Int,
    /** Sum over priced turns; null when no turn was priced. */
    val cost: Double?,
    val costUncached: Double?,
    val pricedTurns: Int,
    val costliestTurn: Double?,
    /** In order of first appearance. */
    val models: List<ModelShare>,
) {
    data class ModelShare(val model: String, val turns: Int, val cost: Double?)

    val promptTokens: Int get() = input + cacheRead + cacheWrite
    val totalTokens: Int get() = promptTokens + output
    val cacheHitRate: Double? get() = if (promptTokens > 0) cacheRead.toDouble() / promptTokens else null
    val saved: Double? get() {
        val c = cost ?: return null
        val u = costUncached ?: return null
        return maxOf(0.0, u - c)
    }
    val averageCost: Double? get() = cost?.takeIf { pricedTurns > 0 }?.let { it / pricedTurns }

    companion object {
        /** One entry per assistant message, null where it carries no usage. */
        fun of(assistantTurns: List<TurnUsage?>): ConversationUsage {
            val metered = assistantTurns.filterNotNull()
            val priced = metered.mapNotNull { it.cost }
            val uncached = metered.mapNotNull { it.costUncached }
            val byModel = LinkedHashMap<String, ModelShare>()
            for (u in metered) {
                val share = byModel[u.model] ?: ModelShare(u.model, 0, null)
                byModel[u.model] = share.copy(
                    turns = share.turns + 1,
                    cost = u.cost?.let { (share.cost ?: 0.0) + it } ?: share.cost,
                )
            }
            return ConversationUsage(
                turns = metered.size,
                unmetered = assistantTurns.size - metered.size,
                rounds = metered.sumOf { it.rounds },
                input = metered.sumOf { it.input },
                output = metered.sumOf { it.output },
                cacheRead = metered.sumOf { it.cacheRead },
                cacheWrite = metered.sumOf { it.cacheWrite },
                cost = if (priced.isEmpty()) null else priced.sum(),
                costUncached = if (uncached.isEmpty()) null else uncached.sum(),
                pricedTurns = priced.size,
                costliestTurn = priced.maxOrNull(),
                models = byModel.values.toList(),
            )
        }
    }
}

/**
 * How cost and token figures are spelled everywhere they appear, so the coin
 * under a reply and the conversation's summary cannot disagree on rounding.
 */
object UsageFormat {
    /**
     * Euros, at a resolution that never rounds a real cost to nothing: none
     * for zero, four decimals under a cent, two otherwise. Written the way
     * [language] writes an amount, as NumberFormatter's currency style does:
     * "€0.42", "€ 0,42" (Dutch), "0,42 €".
     */
    fun money(value: Double, language: String): String {
        val digits = if (value == 0.0) 0 else if (abs(value) < 0.01) 4 else 2
        val figure = fixed(abs(value), digits).let { if (language == "en") it else it.replace('.', ',') }
        val sign = if (value < 0) "-" else ""
        return when (language) {
            "en" -> "$sign€$figure"
            "nl" -> "€$NBSP$sign$figure"
            else -> "$sign$figure$NBSP€"
        }
    }

    /** "850", "12.3k", "1.25M" — as iOS writes them, whatever the language. */
    fun tokens(n: Int): String = when {
        n >= 1_000_000 -> printfFixed(n / 1_000_000.0, 2) + "M"
        n >= 1000 -> printfFixed(n / 1000.0, 1) + "k"
        else -> n.toString()
    }

    /** "82%". */
    fun percent(rate: Double): String = "${(rate * 100).roundToInt()}%"

    /** The coin's figure: the cost when priced, else the token volume — never a false "€0.00". */
    fun headline(usage: TurnUsage, language: String): String =
        usage.cost?.let { money(it, language) } ?: "${tokens(usage.promptTokens + usage.output)} tok"

    /** Beside the coin: "82% cached", "no cache", or nothing when there was no prompt. */
    fun cacheLabel(usage: TurnUsage): String? {
        val rate = usage.cacheHitRate ?: return null
        if (usage.cacheRead == 0) return "no cache"
        return "${percent(rate)} cached"
    }

    /** The no-break space NumberFormatter puts between figure and sign. */
    const val NBSP = "\u00A0"

    private fun fixed(value: Double, digits: Int): String =
        if (digits == 0) value.roundToLong().toString() else printfFixed(value, digits)
}
