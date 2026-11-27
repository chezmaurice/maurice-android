package eu.chezmaurice.parity

import eu.chezmaurice.core.composer.TrayItem
import eu.chezmaurice.core.domains.DomainStore
import eu.chezmaurice.core.domains.DomainsApi
import eu.chezmaurice.core.model.Brief
import eu.chezmaurice.core.model.Domain
import eu.chezmaurice.core.model.DomainProposal
import eu.chezmaurice.core.model.DomainSummary
import eu.chezmaurice.core.model.ModelInfo
import eu.chezmaurice.core.model.SeededNotes
import eu.chezmaurice.core.model.ToolFamily
import eu.chezmaurice.maurice.domains.symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lot C1 (domains & composer): what the domain and composer parity tests
 * read off the Android stores, shaped as `DomainsSupport.swift` shapes it on
 * the iOS side. Readers only: no logic of the app's is redone here.
 */
object DomainsSupport {
    fun row(d: Domain): Map<String, Any?> = mapOf(
        "id" to d.id,
        "name" to d.name,
        "kind" to d.kind,
        "icon" to d.icon,
        "symbol" to d.symbol(),
        "model" to d.model,
        "temp" to d.temp,
        "thinking" to d.thinking,
        "tagline" to d.tagline,
        "prompt" to d.prompt,
        "users" to d.users,
        // What the editor seeds its tray with (DomainEditor: initial.context.mapNotNull(TrayItem::fromSpec)).
        "context" to d.context.mapNotNull(TrayItem::fromSpec).map { it.id },
        "weight" to d.weight,
        "count" to d.count,
        "toolFamilies" to d.toolFamilies,
        "createdBy" to d.createdBy,
        "isCompanion" to d.isCompanion,
        "isDomainOfU1" to d.isDomainOf("u1"),
    )

    fun overview(o: DomainSummary): Map<String, Any?> = mapOf(
        "kind" to o.kind,
        "mine" to o.mine,
        "briefUpdatedAt" to o.brief?.updatedAt,
        "briefByMember" to (o.brief?.byMember ?: false),
        "conversationId" to o.conversationId,
        // The seeded notes are not shown in the list on either side (only on the brief page: DomainsBriefParity).
    )

    fun notes(n: SeededNotes): Map<String, Any?> = mapOf("total" to n.total, "unreviewed" to n.unreviewed, "webPath" to n.webPath)

    fun model(m: ModelInfo): Map<String, Any?> = mapOf(
        "id" to m.id, "name" to m.name, "tier" to m.tier, "provider" to m.provider,
        "sub" to m.sub, "desc" to m.desc, "note" to m.note,
        "available" to m.available, "thinking" to m.thinking,
        "isLocal" to m.isLocal, "thinkingIsOptional" to m.thinkingIsOptional,
    )

    fun family(f: ToolFamily): Map<String, Any?> = mapOf(
        "id" to f.id, "title" to f.title, "icon" to f.icon, "blurb" to f.blurb, "count" to f.count, "group" to f.group, "alwaysOn" to f.alwaysOn,
    )

    fun brief(b: Brief?): Map<String, Any?>? = b?.let {
        mapOf("text" to it.text, "updatedAt" to it.updatedAt, "sources" to it.sources, "model" to it.model, "byMember" to it.byMember)
    }

    fun proposal(p: DomainProposal): Map<String, Any?> = mapOf(
        "id" to p.id, "name" to p.name, "summary" to p.summary, "oneLine" to p.oneLine,
        "state" to p.state, "conversations" to p.conversations, "mailThreads" to p.mailThreads,
        "weight" to p.weight, "isNew" to p.isNew, "isAlive" to p.isAlive, "canRestore" to p.canRestore,
        "domainId" to p.domainId,
    )

    /** The fake household's log, keeping the entries [keep] says yes to. */
    suspend fun requests(keep: (String) -> Boolean = { true }): List<JsonElement> =
        (Parity.requests() as? JsonArray).orEmpty().filter {
            val path = it.jsonObject["path"]?.jsonPrimitive?.content.orEmpty()
            // A chat left over from an earlier test keeps reopening its socket (iOS side).
            !path.endsWith("/ws") && keep(path)
        }

    /** The log without each entry's body (see DomainsSupport.swift). */
    fun withoutBodies(log: List<JsonElement>): List<JsonElement> = log.map { JsonObject(it.jsonObject - "body") }

    /** A scope whose launches can be waited for, one at a time as on the main thread. */
    class Scoped {
        val job: Job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Default.limitedParallelism(1) + job)

        /** Every launch, and the launches they made, done. */
        suspend fun settle() {
            while (true) {
                val running = job.children.filter { it.isActive }.toList()
                if (running.isEmpty()) return
                running.forEach { it.join() }
            }
        }

        /** As [settle], but gives up after [millis]: a chat controller keeps loops of its own running. */
        suspend fun settle(millis: Long) { kotlinx.coroutines.withTimeoutOrNull(millis) { settle() } }

        fun cancel() = job.cancel()
    }

    fun store(s: Scoped): DomainStore = DomainStore(DomainsApi(MauriceFixture.session().api()!!), s.scope)
}
