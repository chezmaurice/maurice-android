import XCTest

/// One turn, end to end (parity/vectors/maurice/chat-turn.json). The case's
/// `routes` serve the setup (the list, the open conversation); `after` is
/// what the household answers to the turn itself.
@MainActor
final class ChatTurnParity: XCTestCase {
    func testChatTurn() async {
        await Parity.run("maurice", "chat-turn") { c in
            let chat = ChatService(session: MauriceFixture.session())
            await chat.loadConversations()
            let openId: String? = (c["open"] is NSNull) ? nil : (c.string("open") ?? "c1")
            if let openId { await chat.selectConversation(openId) }
            await ChatSupport.pause(300)
            await Parity.reset(c["after"] ?? [Any]())

            switch c.string("action") ?? "" {
            case "send":
                let images = (c.array("images") ?? []).compactMap { ($0 as? String).flatMap { Data(base64Encoded: $0) } }
                await chat.send(c.string("text") ?? "", images: images)
            case "regenerate":
                await chat.regenerate()
            case "stop":
                let turn = Task { await chat.send(c.string("text") ?? "") }
                await ChatSupport.pause(300)
                chat.stop()
                await turn.value
            default:
                break
            }
            await ChatSupport.pause(400)
            let log = await ChatSupport.log()
            return [
                "sent": ChatSupport.sent(log),
                "attached": ChatSupport.attached(log),
                "reply": ChatSupport.reply(chat),
                "trails": ChatSupport.trails(chat),
                "thread": ChatSupport.thread(chat, ids: false),
                "banner": ChatSupport.banner(chat),
                "running": chat.isStreaming,
                "active": chat.activeConversationId as Any,
            ]
        }
    }
}
