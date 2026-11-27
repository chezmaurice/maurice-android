import XCTest

/// The shape of MyDataView's private `AccountEventsAnswer`: what the view
/// and the launch alert decode GET /api/me/events into.
private struct SessionEventsAnswer: Decodable { let events: [AccountEvent] }

@MainActor
final class SessionEventsParity: XCTestCase {
    func testAccountEvents() async {
        await Parity.run("maurice", "session-account-events") { c in
            let s = MauriceFixture.session()
            // MyDataView.loadAccountEvents: `try?` — nothing shown when it fails.
            let answer: SessionEventsAnswer? = try? await APIClient(baseURL: s.serverURL!)
                .get("/api/me/events", token: s.tokenForActiveUser!)
            return [
                "requests": await SessionSupport.requests(),
                "events": answer.map { a in a.events.map { e in
                    ["id": e.id, "kind": e.kind, "unseen": e.seen_at == nil, "sentence": e.sentence(s)] as [String: Any]
                } } as Any,
            ]
        }
    }
}
