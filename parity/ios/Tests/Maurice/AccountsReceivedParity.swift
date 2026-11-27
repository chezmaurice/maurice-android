import XCTest

/// A conversation received read-only from another household
/// (parity/vectors/maurice/accounts-received.json). `routes` serve the
/// setup, `after` the way on.
@MainActor
final class AccountsReceivedParity: XCTestCase {
    func testReceived() async {
        await Parity.run("maurice", "accounts-received") { c in
            let chat = ChatService(session: MauriceFixture.session())
            await chat.loadConversations()
            if let openId = c.string("open") { await chat.selectConversation(openId) }
            await ChatSupport.pause(300)
            let received: Any = chat.activeReceivedFrom ?? NSNull()
            var out: [String: Any] = ["received": received]
            if c.string("action") == "continue" {
                await Parity.reset(c["after"] ?? [Any]())
                await chat.startFromReceived()
                await ChatSupport.pause(400)
                let log = await ChatSupport.log()
                // POST …/read is the chat-thread lot's (its body differs by `wire`).
                out["sent"] = ChatSupport.sent(log).filter { !(($0["path"] as? String) ?? "").hasSuffix("/read") }
                out["after"] = chat.activeReceivedFrom ?? NSNull()
            }
            out["active"] = chat.activeConversationId ?? NSNull()
            out["entry"] = chat.activeEntry?.ref ?? NSNull()
            out["list"] = chat.conversations.map(\.id)
            out["banner"] = ChatSupport.banner(chat)
            return out
        }
    }
}
