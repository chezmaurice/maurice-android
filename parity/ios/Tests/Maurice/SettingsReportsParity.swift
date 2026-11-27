import XCTest

/// Reporting and the admin's queue (lot Settings): ChatService's safety calls,
/// the reasons the report sheets offer and their labels.
@MainActor
final class SettingsReportsParity: XCTestCase {
    func testSettingsReports() async {
        await Parity.run("maurice", "settings-reports") { c in
            switch c.string("kind") {
            case "queue":
                let chat = ChatService(session: MauriceFixture.session(role: "admin"))
                let list = await chat.loadOpenReports()
                return [
                    "requests": await SettingsSupport.requests("/api/reports"),
                    "reports": list.map { $0.map(Self.view) as Any } ?? NSNull(),
                    "error": chat.error != nil,
                ]
            case "act", "dismiss":
                let chat = ChatService(session: MauriceFixture.session(role: "admin"))
                let id = c.string("id") ?? ""
                if c.string("kind") == "act" {
                    await chat.actionReport(id, removeMessage: c.bool("remove") ?? false, ejectMember: c.bool("eject") ?? false)
                } else {
                    await chat.dismissReport(id)
                }
                return [
                    "requests": await SettingsSupport.requests("/api/reports"),
                    "error": chat.error != nil,
                ]
            case "report":
                let chat = ChatService(session: MauriceFixture.session())
                chat.activeConversationId = "c1"
                let sent = await chat.report(targetType: c.string("targetType") ?? "", targetId: c.string("targetId") ?? "",
                                             reason: c.string("reason") ?? "", note: c.string("note"))
                return [
                    "requests": await SettingsSupport.requests("/api/conversations/c1/reports"),
                    "sent": sent,
                    "error": chat.error != nil,
                ]
            case "reasons":
                return [
                    "room": ReportReason.allCases.map { ["reason": $0.rawValue, "label": $0.label] },
                    "reply": AIReplyReason.allCases.map { ["reason": $0.rawValue, "label": $0.label] },
                ]
            default:
                return ["unknown kind": c.string("kind") as Any]
            }
        }
    }

    /// What a queue card shows of a report.
    static func view(_ r: ReportView) -> [String: Any?] {
        [
            "id": r.id,
            "reason": r.reason,
            "targetType": r.target_type,
            "roomTitle": r.room_title,
            "message": r.reported_message?.content,
            "member": r.reported_member?.display_name,
            "question": r.reported_question,
            "note": r.note,
            "reporter": r.reporter_display_name,
        ]
    }
}
