import XCTest

/// What was shared to Carnet and had not reached the household, sent again
/// (lot "Carnet shelf & entries"): which captures go, in what order, as whom,
/// and which stay on the device.
@MainActor
final class ShelfOutboxParity: XCTestCase {
    func testOutbox() async {
        await Parity.run("carnet", "shelf-outbox") { c in
            let session = ShelfFixture.session()
            let hid = session.currentHouseholdId!
            if c.bool("otherSignedIn") == true {
                if !TokenVault.set("tok-u2", CarnetSession.tokenKey(hid, "u2")) {
                    CarnetAPI.defaults.set("tok-u2", forKey: CarnetSession.tokenKey(hid, "u2"))
                }
            }
            let start = Date(timeIntervalSince1970: 1_800_000_000)
            for raw in c.array("items") as? [[String: Any]] ?? [] {
                let member = raw["member"] as? String ?? "u1"
                let item = QueueItem(
                    id: UUID(), type: .articleScrape,
                    createdAt: start.addingTimeInterval((raw["at"] as? NSNumber)?.doubleValue ?? 0),
                    payload: QueuePayload(url: raw["url"] as? String, comment: raw["comment"] as? String, title: raw["title"] as? String),
                    origin: QueueOrigin(householdId: hid, memberId: member, serverURL: Parity.baseURL))
                OfflineQueue.shared.enqueue(item)
            }
            await SyncService().syncNow()
            let left = OfflineQueue.shared.pendingItems()
            let requests = await ShelfObserve.requests(prefix: "/api/v1/garden/articles")
            for item in left { OfflineQueue.shared.dequeue(item.id) }
            return [
                // Which surface captured it is the server's triage label, never shown.
                "requests": requests.map(ShelfOutbox.withoutClient),
                "left": left.map { $0.payload.url ?? "" }.sorted(),
            ]
        }
    }
}

enum ShelfOutbox {
    static func withoutClient(_ request: Any) -> Any {
        guard var r = request as? [String: Any], var body = r["body"] as? [String: Any] else { return request }
        body.removeValue(forKey: "source_client")
        r["body"] = body
        return r
    }
}
