import XCTest

/// A tool's words, a library book in the suggestions, taking someone out of a
/// room (parity/vectors/maurice/content-chat.json).
@MainActor
final class ContentChatParity: XCTestCase {
    func testContentChat() async {
        await Parity.run("maurice", "content-chat") { c in
            switch c.string("op") {
            case "tool":
                let tool = c.string("tool") ?? ""
                let running = c.bool("running") ?? false
                return ["label": ToolStep.label(for: tool, running: running),
                        "name": ToolStep(tool: tool, count: c.int("count") ?? 1, running: running).name]
            case "suggestion":
                guard let rows = try? JSONDecoder().decode([EntrySuggestion].self, from: c.data("rows")) else { return ["rows": "undecodable"] }
                return ["rows": rows.map { ["id": $0.id, "existing": $0.existing, "inLibrary": $0.in_library as Any] }]
            case "remove":
                let chat = ChatService(session: MauriceFixture.session())
                await chat.loadConversations()
                if let openId = c.string("open") { await chat.selectConversation(openId) }
                await ChatSupport.pause(300)
                await Parity.reset(c["after"] ?? [Any]())
                await chat.removeParticipant(memberId: c.string("member") ?? "")
                await ChatSupport.pause(400)
                return ["sent": ChatSupport.sent(await ChatSupport.log()),
                        "participants": ChatSupport.participants(chat),
                        "banner": ChatSupport.banner(chat)]
            default:
                return ["unknown op": c.string("op") as Any]
            }
        }
    }
}
