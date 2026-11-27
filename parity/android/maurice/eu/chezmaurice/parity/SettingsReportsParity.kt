package eu.chezmaurice.parity

import eu.chezmaurice.core.chat.ChatApi
import eu.chezmaurice.core.chat.ChatController
import eu.chezmaurice.core.model.Role
import eu.chezmaurice.core.settings.Report
import eu.chezmaurice.core.settings.ReportsApi
import kotlin.test.Test

/** Reporting and the admin's queue (lot Settings). */
class SettingsReportsParity {
    @Test fun settingsReports() = Parity.check("maurice", "settings-reports") { c ->
        when (c.string("kind")) {
            "queue" -> {
                val api = ReportsApi(MauriceFixture.session(role = Role.Admin).api()!!)
                var error = false
                val list = try { api.open() } catch (e: Throwable) { error = true; null }
                mapOf(
                    "requests" to SettingsSupport.requests("/api/reports"),
                    "reports" to list?.map(::view),
                    "error" to error,
                )
            }
            "act", "dismiss" -> {
                val api = ReportsApi(MauriceFixture.session(role = Role.Admin).api()!!)
                val id = c.string("id") ?: ""
                val error = try {
                    if (c.string("kind") == "act") api.act(id, removeMessage = c.bool("remove") ?: false, ejectMember = c.bool("eject") ?: false)
                    else api.dismiss(id)
                    false
                } catch (e: Throwable) { true }
                mapOf("requests" to SettingsSupport.requests("/api/reports"), "error" to error)
            }
            "report" -> {
                val chat = ChatController(ChatApi(MauriceFixture.session().api()!!), SettingsSupport.inert, "u1")
                chat.open("c1")
                val sent = chat.report(c.string("targetType") ?: "", c.string("targetId") ?: "", c.string("reason") ?: "", c.string("note"))
                mapOf(
                    "requests" to SettingsSupport.requests("/api/conversations/c1/reports"),
                    "sent" to sent,
                    "error" to (chat.state.value.error != null),
                )
            }
            "reasons" -> {
                val file = "eu.chezmaurice.maurice.chat.DetailsKt"
                @Suppress("UNCHECKED_CAST") val room = SettingsSupport.privateTopLevel(file, "REASONS") as List<String>
                @Suppress("UNCHECKED_CAST") val reply = SettingsSupport.privateTopLevel(file, "AI_REASONS") as List<String>
                val s = SettingsSupport.strings
                mapOf(
                    // The labels as the sheets write them: L("report.reason.$r"), L("report.ai.reason.$r").
                    "room" to room.map { mapOf("reason" to it, "label" to s("report.reason.$it")) },
                    "reply" to reply.map { mapOf("reason" to it, "label" to s("report.ai.reason.$it")) },
                )
            }
            else -> mapOf("unknown kind" to c.string("kind"))
        }
    }

    private fun view(r: Report) = mapOf(
        "id" to r.id,
        "reason" to r.reason,
        "targetType" to r.targetType,
        "roomTitle" to r.roomTitle,
        "message" to r.reportedMessage?.content,
        "member" to r.reportedMember?.displayName,
        "question" to r.reportedQuestion,
        "note" to r.note,
        "reporter" to r.reporterDisplayName,
    )
}
