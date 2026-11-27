package eu.chezmaurice.core.budget

import eu.chezmaurice.core.net.ApiClient
import eu.chezmaurice.core.store.KeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * The household's budget, as GET /api/me/budget gives it: the same for every
 * member, a fraction left — never an amount, except to the admin, who pays
 * ([leftEur]). `limited == false` when nothing limits the household.
 */
@Serializable
data class HouseholdBudget(
    val limited: Boolean = false,
    /** "balance" (prepaid, a hosted household) or "monthly"; null when unlimited. */
    val kind: String? = null,
    /** What is left, 0…1; null when unlimited. */
    val left: Double? = null,
    /** When a monthly budget starts afresh (ISO 8601, UTC). */
    @SerialName("resets_at") val resetsAt: String? = null,
    /** Changes when the budget is filled again — a new month, a top-up. */
    val period: String? = null,
    /** What is left in euros: the admin's only. */
    @SerialName("left_eur") val leftEur: Double? = null,
    /** The admin of a hosted household can top it up (Settings → Abonnement). */
    @SerialName("can_top_up") val canTopUp: Boolean = false,
)

data class BudgetState(
    val budget: HouseholdBudget? = null,
    /** The threshold just crossed, in percent, while its toast is up. */
    val alert: Int? = null,
) {
    /** Whether the household holds a budget at all: without one, no ring and no Budget row. */
    val isLimited: Boolean get() = budget?.limited == true
    /** What the ring draws: the fraction left, or null for "no budget". */
    val left: Double? get() = if (isLimited) budget?.left else null
    val leftPercent: Int? get() = left?.let { (it * 100).roundToInt() }
}

/**
 * The budget ring's state and the alerts it raises, as `BudgetStore.swift`:
 * a toast once the household's budget falls to 20 %, again at 10 % and 5 %,
 * each once per member and period, remembered on the device under [memberKey].
 */
class BudgetStore(
    private val api: ApiClient,
    private val store: KeyValueStore,
    private val memberKey: String,
) {
    private val _state = MutableStateFlow(BudgetState())
    val state: StateFlow<BudgetState> = _state.asStateFlow()

    suspend fun refresh() {
        // A server from before the budget has no route: stay as we were.
        val b = try {
            api.get<HouseholdBudget>("/api/me/budget")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        _state.update { it.copy(budget = b) }
        raiseAlertIfDue(b)
    }

    fun dismissAlert() = _state.update { it.copy(alert = null) }

    /** The deepest threshold the budget has fallen to, told once. */
    private fun raiseAlertIfDue(b: HouseholdBudget) {
        val left = b.left ?: return
        val period = b.period ?: b.resetsAt ?: return
        if (!b.limited) return
        val crossed = THRESHOLDS.filter { left <= it }.minOrNull() ?: return
        val key = "budget.alerted.$memberKey.$period"
        val told = store.get(key)?.toDoubleOrNull() ?: 1.0
        if (crossed >= told) return
        store.put(key, crossed.toString())
        _state.update { it.copy(alert = (crossed * 100).roundToInt()) }
    }

    companion object {
        /** The fractions left at which the member is told, largest first. */
        val THRESHOLDS = listOf(0.20, 0.10, 0.05)
    }
}
