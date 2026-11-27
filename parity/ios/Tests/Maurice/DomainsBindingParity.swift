import XCTest

@MainActor
final class DomainsBindingParity: XCTestCase {
    func testDomainsBinding() async {
        await Parity.run("maurice", "domains-binding") { c in
            let chat = ChatService(session: MauriceFixture.session())
            await chat.loadConversations()
            let id = c.string("conversation") ?? ""
            let ok = await chat.setDomain(c.string("domain"), forConversation: id)
            return [
                "requests": await DomainsSupport.requests { $0.hasSuffix("/maurice") },
                "ok": ok,
                "rows": chat.conversations.map { ["id": $0.id, "domain": $0.maurice_id as Any, "boundBy": $0.maurice_bound_by as Any] },
                "error": chat.error != nil,
            ]
        }
    }
}
