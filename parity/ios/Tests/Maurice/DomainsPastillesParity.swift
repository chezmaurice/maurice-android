import XCTest

@MainActor
final class DomainsPastillesParity: XCTestCase {
    func testDomainsPastilles() async {
        await Parity.run("maurice", "domains-pastilles") { c in
            let session = MauriceFixture.session()
            let store = MauriceStore(session: session)
            store.maurices = (c.array("rows") as? [[String: Any]] ?? []).compactMap(Maurice.parse)
            let chat = ChatService(session: session)
            let convo = try JSONDecoder().decode(ServerConversation.self, from: c.data("conversation"))
            chat.conversations = [convo]
            chat.activeConversationId = convo.id
            chat.messages = try JSONDecoder().decode([ServerMessage].self, from: c.data("messages"))
            return ["pastilles": DomainPastilles.mobilised(chat: chat, store: store, session: session).map { p -> [String: Any] in
                let kind: String
                switch p.kind {
                case .bound(auto: false): kind = "bound"
                case .bound(auto: true): kind = "boundAuto"
                case .read: kind = "read"
                case .recognised: kind = "recognised"
                }
                return ["id": p.maurice.id, "kind": kind]
            }]
        }
    }
}
