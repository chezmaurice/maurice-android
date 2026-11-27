import XCTest

/// A conversation opened, and what can be done with it
/// (parity/vectors/maurice/chat-thread.json). `routes` serve the setup,
/// `after` the action.
@MainActor
final class ChatThreadParity: XCTestCase {
    func testChatThread() async {
        await Parity.run("maurice", "chat-thread") { c in
            let chat = ChatService(session: MauriceFixture.session())
            await chat.loadConversations()
            if let openId = c.string("open") { await chat.selectConversation(openId) }
            await ChatSupport.pause(300)
            await Parity.reset(c["after"] ?? [Any]())

            var result: Any = NSNull()
            let action = c.string("action") ?? ""
            switch action {
            case "open":
                await chat.selectConversation(c.string("id") ?? "")
            case "rename":
                await chat.renameConversation(c.string("id") ?? "", title: c.string("title") ?? "")
            case "delete":
                await chat.deleteConversation(c.string("id") ?? "")
            case "search":
                chat.searchConversations(c.string("q") ?? "")
                await ChatSupport.pause(800)
                let hits: [[String: Any]] = chat.searchResults.map { h -> [String: Any] in
                    let message: Any = h.message_id ?? NSNull()
                    return ["id": h.conversation.id, "snippet": h.snippet, "hits": h.hits, "message": message]
                }
                result = ["results": hits]
            case "more":
                for _ in 0..<(c.int("times") ?? 1) { await chat.loadMoreConversations() }
            case "add":
                await chat.addParticipant(memberId: c.string("member") ?? "")
            case "leave":
                await chat.leaveRoom()
            case "block":
                await chat.block(memberId: c.string("member") ?? "")
            case "domain":
                result = await chat.setDomain(c.string("domain"), forConversation: chat.activeConversationId ?? "")
            case "report":
                result = await chat.report(targetType: "message", targetId: c.string("target") ?? "",
                                           reason: c.string("reason") ?? "", note: c.string("note"))
            default:
                break
            }
            await ChatSupport.pause(400)
            let log = await ChatSupport.log()
            var out: [String: Any] = [
                "sent": ChatSupport.sent(log),
                "active": chat.activeConversationId as Any,
                "list": ChatSupport.list(chat),
                "unread": chat.unread.sorted(),
                "thread": ChatSupport.thread(chat, ids: true),
                "participants": ChatSupport.participants(chat),
                "room": chat.participants.count > 1,
                "entry": chat.activeEntry?.ref as Any,
                "banner": ChatSupport.banner(chat),
                "result": result,
            ]
            if action == "search" || action == "more" { out["reads"] = ChatSupport.listReads(log) }
            return out
        }
    }
}
