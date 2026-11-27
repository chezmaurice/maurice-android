package eu.chezmaurice.core.domains

import eu.chezmaurice.core.model.BriefRefresh
import eu.chezmaurice.core.model.Domain
import eu.chezmaurice.core.model.DomainProposal
import eu.chezmaurice.core.model.DomainSummary
import eu.chezmaurice.core.model.ModelInfo
import eu.chezmaurice.core.model.ProposalDecision
import eu.chezmaurice.core.model.ProposalOutcome
import eu.chezmaurice.core.model.ToolFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class DomainsState(
    /** The rows, sorted by name as the server sends them. */
    val rows: List<Domain> = emptyList(),
    /** `GET /api/domains`, by row id: a brief's date, a companion's conversation. */
    val overview: Map<String, DomainSummary> = emptyMap(),
    /** What the night proposes and the member has not settled. */
    val proposals: List<DomainProposal> = emptyList(),
    val settled: List<DomainProposal> = emptyList(),
    /** Proposals made since the list was last opened: the badge. */
    val unseen: Int = 0,
    val models: List<ModelInfo> = emptyList(),
    /** The member's model for the everyday Maurice; null = the household default. */
    val everydayModelId: String? = null,
    val families: List<ToolFamily> = emptyList(),
) {
    /** The member's domains (their own, or those granted to a guest). */
    val domains: List<Domain> get() = rows.filter { it.kind == "domain" }
    val companions: List<Domain> get() = rows.filter { it.isCompanion }

    fun row(id: String?): Domain? = id?.let { i -> rows.firstOrNull { it.id == i } }

    val defaultModelId: String? get() = models.firstOrNull { it.isDefault }?.id

    /**
     * The model a conversation runs, as the server resolves it: the domain's
     * own, else (everyday) the member's choice, else the household default.
     */
    fun resolvedModel(domain: Domain?): ModelInfo? {
        val id = domain?.model ?: (if (domain == null) everydayModelId else null) ?: defaultModelId
        return models.firstOrNull { it.id == id } ?: models.firstOrNull()
    }

    fun model(id: String?): ModelInfo? = (id ?: defaultModelId)?.let { i -> models.firstOrNull { it.id == i } } ?: models.firstOrNull()
}

/**
 * The member's domains and reading companions, the night's proposals, the
 * model roster — `MauriceStore.swift` with the proposal half of
 * `ChatService.swift`. One per signed-in member.
 */
class DomainStore(private val api: DomainsApi, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(DomainsState())
    val state: StateFlow<DomainsState> = _state.asStateFlow()

    /** Everything, as on sign-in. A part that fails leaves the rest. */
    fun load() = scope.launch {
        loadRows()
        loadProposals()
        quietly { _state.update { it.copy(models = api.models()) } }
        quietly { _state.update { it.copy(everydayModelId = api.everydayModel()) } }
        quietly { _state.update { it.copy(families = api.families()) } }
    }

    fun reloadRows() = scope.launch { loadRows() }

    /** The rows, then what the list shows beside them; rows that fail to load leave both as they were (`loadMaurices`). */
    private suspend fun loadRows() {
        if (attempt { _state.update { it.copy(rows = api.rows()) } }) loadOverview()
    }

    suspend fun loadOverview() = quietly {
        val o = api.overview()
        _state.update { it.copy(overview = (o.domains + o.companions).associateBy { r -> r.id }) }
    }

    suspend fun loadProposals() = quietly {
        val p = api.proposals()
        _state.update { it.copy(proposals = p.proposals, settled = p.settled, unseen = p.unseen) }
    }

    /** The list was opened: the badge goes; the rows keep their "new" mark for this visit. */
    suspend fun markProposalsSeen() {
        if (_state.value.unseen == 0) return
        quietly { val c = api.markProposalsSeen(); _state.update { it.copy(unseen = c.unseen) } }
    }

    /**
     * Switch the model of a conversation's Maurice: the everyday one keeps it
     * per member, a domain on its row. Applies to this and later turns.
     */
    fun setModel(modelId: String, domain: Domain?) = scope.launch {
        quietly {
            if (domain == null) {
                val kept = api.setEverydayModel(modelId)
                _state.update { it.copy(everydayModelId = kept) }
            } else {
                replace(api.patch(domain.id, JsonObject(mapOf("model" to JsonPrimitive(modelId)))), addMissing = false)
            }
        }
    }

    /** Create or edit; the list takes the saved row. */
    suspend fun save(id: String?, fields: JsonObject): SaveOutcome {
        val outcome = try { api.save(id, fields) } catch (e: CancellationException) { throw e } catch (_: Throwable) { SaveOutcome.Failed }
        if (outcome is SaveOutcome.Saved) { replace(outcome.row); loadOverview() }
        return outcome
    }

    suspend fun delete(id: String) {
        api.delete(id)
        _state.update { s -> s.copy(rows = s.rows.filterNot { it.id == id }, overview = s.overview - id) }
    }

    // ── Briefs ─────────────────────────────────────────────

    suspend fun brief(id: String) = api.brief(id)
    suspend fun saveBrief(id: String, text: String) = api.saveBrief(id, text).also { loadOverview() }
    suspend fun deleteBrief(id: String) = api.deleteBrief(id).also { loadOverview() }
    suspend fun refreshBrief(id: String): BriefRefresh = api.refreshBrief(id).also { loadOverview() }
    suspend fun boundConversations(id: String, autoOnly: Boolean = true) = runCatching { api.conversations(id, autoOnly) }.getOrNull()

    // ── Proposals ──────────────────────────────────────────

    suspend fun proposal(id: String) = runCatching { api.proposal(id) }.getOrNull()

    /** The member's word; null when the server could not be reached. An adoption adds a domain. */
    suspend fun apply(decisions: List<ProposalDecision>): ProposalOutcome? {
        val outcome = runCatching { api.apply(decisions) }.getOrNull() ?: return null
        loadProposals()
        if (outcome.adopted.isNotEmpty()) loadRows()
        return outcome
    }

    suspend fun restore(id: String): Boolean = attempt { api.restore(id) }.also { loadProposals() }
    suspend fun merge(ids: List<String>, name: String?): Boolean = attempt { api.merge(ids, name) }.also { loadProposals() }
    suspend fun split(id: String, name: String, conversationIds: List<String>, mail: List<String>): Boolean =
        attempt { api.split(id, name, conversationIds, mail) }.also { loadProposals() }

    /** Put the store in a given state without a server — previews and screenshots only. */
    fun seed(state: DomainsState) { _state.value = state }

    /** The saved row in place of its old self; a row the list lacks is added only by a save ([addMissing]). */
    private fun replace(row: Domain, addMissing: Boolean = true) = _state.update { s ->
        val known = s.rows.any { it.id == row.id }
        if (!known && !addMissing) return@update s
        val rows = if (known) s.rows.map { if (it.id == row.id) row else it } else s.rows + row
        s.copy(rows = rows.sortedWith { a, b -> compareNames(a.name, b.name) })
    }

    private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
        block(); true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        false
    }

    /** A list that fails to load shows what it had; nothing more (as on iOS). */
    private suspend fun quietly(block: suspend () -> Unit) { attempt(block) }
}
