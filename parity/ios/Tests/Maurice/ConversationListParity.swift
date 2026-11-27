import XCTest

@MainActor
final class ConversationListParity: XCTestCase {
    func testConversationList() async {
        await Parity.run("maurice", "conversation-list") { c in
            let chat = ChatService(session: MauriceFixture.session())
            await chat.loadConversations()
            let requests = (await Parity.requests() as? [[String: Any]] ?? [])
                .filter { ($0["path"] as? String)?.hasPrefix("/api/conversations") == true }
            return [
                "requests": requests,
                "conversations": chat.conversations.map { ["id": $0.id, "title": $0.title as Any] },
                "unread": chat.unread.sorted(),
                "error": chat.error != nil,
            ]
        }
    }
}
