import XCTest

/// EPUBs and PDFs shared to Carnet that the library had not taken, sent
/// again (ed512a3: `SyncService`'s `.libraryBook`): which go, which stay.
@MainActor
final class ShelfBookOutboxParity: XCTestCase {
    func testBookOutbox() async {
        await Parity.run("carnet", "shelf-book-outbox") { c in
            let session = ShelfFixture.session()
            let hid = session.currentHouseholdId!
            let start = Date(timeIntervalSince1970: 1_800_000_000)
            for raw in c.array("books") as? [[String: Any]] ?? [] {
                let filename = raw["filename"] as? String ?? ""
                let item = QueueItem(
                    id: UUID(), type: .libraryBook,
                    createdAt: start.addingTimeInterval((raw["at"] as? NSNumber)?.doubleValue ?? 0),
                    payload: QueuePayload(filename: filename, mimeType: filename.hasSuffix(".pdf") ? "application/pdf" : "application/octet-stream", sourceClient: "ios-share"),
                    origin: QueueOrigin(householdId: hid, memberId: "u1", serverURL: Parity.baseURL))
                OfflineQueue.shared.enqueue(item, fileData: Data(((raw["bytes"] as? String) ?? "x").utf8))
            }
            await SyncService().syncNow()
            let left = OfflineQueue.shared.pendingItems()
            let requests = await ShelfObserve.requests(prefix: "/api/v1/calibre")
            for item in left { OfflineQueue.shared.dequeue(item.id) }
            return [
                "requests": requests.map { r -> Any in
                    guard var r = r as? [String: Any] else { return r }
                    r["body"] = nil
                    return r
                },
                "left": left.map { $0.payload.filename ?? "" }.sorted(),
            ]
        }
    }
}
