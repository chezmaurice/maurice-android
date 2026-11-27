package eu.chezmaurice.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import eu.chezmaurice.core.domains.LenientBool
import eu.chezmaurice.core.domains.LenientInt
import eu.chezmaurice.core.domains.TolerantList

/**
 * A row of `GET /api/maurices`: a domain of the member's life ("domain") or a
 * reading companion ("companion") — the Swift `Maurice`. The everyday
 * Maurice is no row: a conversation without one (`maurice_id` null).
 */
@Serializable
data class Domain(
    val id: String,
    val name: String,
    val kind: String = "domain",
    /** The SF Symbol the night or the member chose; see `DomainGlyphs`. */
    val icon: String? = null,
    /** Null: the household default. */
    val model: String? = null,
    val temp: Double = 0.5,
    /** Only for a model that reasons optionally; null = the provider's default. */
    val thinking: Boolean? = null,
    val tagline: String = "",
    val prompt: String = "",
    val users: List<String> = emptyList(),
    /** The baked-in context: composer items, each with its `snapshot`. */
    val context: List<JsonObject> = emptyList(),
    @Serializable(with = LenientInt::class) val weight: Int = 0,
    @Serializable(with = LenientInt::class) val count: Int = 0,
    /** Null: inherit. */
    @SerialName("tool_families") val toolFamilies: List<String>? = null,
    @SerialName("created_by") val createdBy: String? = null,
) {
    val isCompanion: Boolean get() = kind == "companion"

    /** The member's own domain — one with a brief they read, correct and erase (`isDomain(of:)`). */
    fun isDomainOf(memberId: String): Boolean = kind == "domain" && createdBy != null && createdBy == memberId
}

/** What the domains list shows beside a row (`GET /api/domains`). */
@Serializable
data class DomainsOverview(
    @Serializable(with = SummaryList::class) val domains: List<DomainSummary> = emptyList(),
    @Serializable(with = SummaryList::class) val companions: List<DomainSummary> = emptyList(),
    val proposals: ProposalCounts? = null,
)

/** A line without an id is left out, the others kept (`DomainOverview.parse`). */
object SummaryList : KSerializer<List<DomainSummary>> by TolerantList(DomainSummary.serializer())

@Serializable
data class DomainSummary(
    val id: String,
    val name: String = "",
    val kind: String = "domain",
    val mine: Boolean = true,
    /** A domain's brief: when it was last written, and by whom (`"member"`). */
    val brief: BriefStamp? = null,
    val notes: SeededNotes? = null,
    /** A companion: its pinned conversation. */
    @SerialName("conversation_id") val conversationId: String? = null,
    /** A companion: the book it follows, as the composer stored it (a number for a Calibre book). */
    @SerialName("book_id") val bookRef: kotlinx.serialization.json.JsonElement? = null,
) {
    /** Calibre's numeric id, when the ref is one. */
    val bookId: Int? get() = (bookRef as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
}

/** [updatedAt] null: a brief without its date, which the list shows as none yet (`briefUpdatedAt`). */
@Serializable
data class BriefStamp(@SerialName("updated_at") val updatedAt: String? = null, val model: String? = null) {
    val byMember: Boolean get() = model == "member"
}

/** The notes Maurice seeded in the garden when the domain was adopted. */
@Serializable
data class SeededNotes(val total: Int = 0, val unreviewed: Int = 0, @SerialName("web_path") val webPath: String? = null)

/** The brief Maurice keeps on a domain: his working memory on it, made visible. */
@Serializable
data class Brief(
    val text: String,
    @SerialName("updated_at") val updatedAt: String,
    /** Conversation ids (and `mail:<path>`) the last rewrite read. */
    val sources: List<String> = emptyList(),
    /** The model that wrote it, or `"member"`. */
    val model: String? = null,
) {
    val byMember: Boolean get() = model == "member"
}

/** `GET /api/domains/:id/brief`. */
@Serializable
data class BriefPage(val brief: Brief? = null, val notes: SeededNotes? = null)

/** What "rewrite now" came to. */
sealed interface BriefRefresh {
    data class Written(val sources: Int) : BriefRefresh
    data object Unchanged : BriefRefresh
    /** The night's allowance is spent for today. */
    data object Capped : BriefRefresh
    data class Failed(val why: String) : BriefRefresh
}

/** `GET /api/domains/:id/conversations`: what is bound there, and how it came. */
@Serializable
data class DomainConversations(
    val conversations: List<Row> = emptyList(),
    val total: Int = 0,
    val auto: Int = 0,
) {
    @Serializable
    data class Row(
        val id: String,
        val title: String? = null,
        @SerialName("bound_by") val boundBy: String? = null,
        @SerialName("updated_at") val updatedAt: String? = null,
    ) {
        val isAuto: Boolean get() = boundBy == "auto"
    }
}

/** A domain the night proposes from unattached conversations and mail threads. */
@Serializable
data class DomainProposal(
    val id: String,
    val name: String,
    val summary: String = "",
    @SerialName("one_line") val oneLine: String? = null,
    val state: String = "proposed",
    val verdict: String? = null,
    val conversations: Int = 0,
    @SerialName("mail_threads") val mailThreads: Int? = null,
    /** 1–5 dots, relative to the biggest. */
    val weight: Int = 0,
    val share: Int = 0,
    @SerialName("recent_90_days") val recent90Days: Int? = null,
    @SerialName("split_hint") val splitHint: String? = null,
    val from: String? = null,
    val to: String? = null,
    @SerialName("domain_id") val domainId: String? = null,
    @SerialName("is_new") val isNew: Boolean? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val sample: List<String>? = null,
) {
    val isAlive: Boolean get() = verdict != "lived"
    /** Put away by the member, or by the old six-week rule: can come back. */
    val canRestore: Boolean get() = state == "dismissed" || state == "expired"
}

@Serializable
data class ProposalCounts(val open: Int = 0, val unseen: Int = 0)

/** `GET /api/domains/proposals`. */
@Serializable
data class ProposalsList(
    @SerialName("total_conversations") val totalConversations: Int = 0,
    val proposals: List<DomainProposal> = emptyList(),
    val settled: List<DomainProposal> = emptyList(),
    val unseen: Int = 0,
)

/** A proposal with everything it holds, for its page. */
@Serializable
data class ProposalDetail(
    val id: String,
    val name: String = "",
    val summary: String = "",
    val state: String = "",
    @SerialName("conversations_list") val conversationsList: List<Line> = emptyList(),
    @SerialName("mail_list") val mailList: List<Thread> = emptyList(),
) {
    @Serializable
    data class Line(val id: String, val date: String? = null, val title: String? = null)

    @Serializable
    data class Thread(val path: String, val from: String? = null, val to: String? = null, val title: String? = null)
}

/** The member's word on one proposal. [action]: adopt, dismiss, or keep (rename only). */
data class ProposalDecision(val id: String, val action: String, val name: String? = null, val summary: String? = null, val seed: Boolean = false)

@Serializable
data class ProposalOutcome(
    val adopted: List<Adopted> = emptyList(),
    val errors: List<Failed> = emptyList(),
) {
    @Serializable
    data class Adopted(val id: String, val name: String = "", @SerialName("domain_id") val domainId: String = "")

    @Serializable
    data class Failed(val id: String, val error: String = "")
}

/** A model of the household's roster (`GET /api/models`). */
@Serializable
data class ModelInfo(
    val id: String,
    val name: String,
    val tier: String = "cloud",
    /** Absent: Anthropic, as iOS reads it. */
    val provider: String = "anthropic",
    val sub: String = "",
    val desc: String = "",
    val note: String = "",
    val available: Boolean = true,
    /** "none" | "optional" | "always". */
    val thinking: String = "none",
    @SerialName("is_default") val isDefault: Boolean = false,
) {
    val isLocal: Boolean get() = tier == "local"
    val thinkingIsOptional: Boolean get() = thinking == "optional"
}

/** A group of tools a domain may be given (`GET /api/tool-families`). */
@Serializable
data class ToolFamily(
    val id: String,
    val title: String,
    val icon: String = "wrench.and.screwdriver",
    val blurb: String = "",
    @Serializable(with = LenientInt::class) val count: Int = 0,
    /** core | garden | experimental. */
    val group: String = "experimental",
    @Serializable(with = LenientBool::class) val alwaysOn: Boolean = false,
)
