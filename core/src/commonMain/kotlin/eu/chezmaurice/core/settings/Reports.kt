package eu.chezmaurice.core.settings

import eu.chezmaurice.core.net.ApiClient
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A report waiting for the household's admin (`GET /api/reports`): a message
 * or a member of a shared room, or the copy of a reply of Maurice's
 * (`ai_reply`) with the question before it — never a private conversation itself.
 */
@Serializable
data class Report(
    val id: String,
    @SerialName("room_id") val roomId: String = "",
    /** message | member | ai_reply. */
    @SerialName("target_type") val targetType: String,
    @SerialName("target_id") val targetId: String = "",
    val reason: String,
    val note: String? = null,
    val status: String = "open",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("reporter_display_name") val reporterDisplayName: String? = null,
    @SerialName("room_title") val roomTitle: String? = null,
    @SerialName("reported_message") val reportedMessage: ReportedMessage? = null,
    @SerialName("reported_member") val reportedMember: ReportedMember? = null,
    @SerialName("reported_question") val reportedQuestion: String? = null,
) {
    @Serializable data class ReportedMessage(val id: String, val content: String = "", @SerialName("author_id") val authorId: String? = null)
    @Serializable data class ReportedMember(val id: String, @SerialName("display_name") val displayName: String = "", val username: String = "")

    val isAiReply: Boolean get() = targetType == "ai_reply"
}

/** The admin's queue of reports (`ReportsReviewView`): child safety first, as the server orders it. */
class ReportsApi(private val api: ApiClient) {
    suspend fun open(): List<Report> = api.get("/api/reports?status=open")

    /** Act on a report: [removeMessage] takes the message out of the room, [ejectMember] its author out of it. Neither: handled. */
    suspend fun act(id: String, removeMessage: Boolean, ejectMember: Boolean) {
        api.post<JsonObject>("/api/reports/${id.encodeURLPathPart()}/action", buildJsonObject {
            put("remove_message", removeMessage); put("eject_member", ejectMember)
        })
    }

    suspend fun dismiss(id: String) {
        api.post<JsonObject>("/api/reports/${id.encodeURLPathPart()}/dismiss", buildJsonObject {})
    }
}
