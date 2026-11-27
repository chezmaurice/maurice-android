import XCTest

/// The list of entries and one entry: what the app decodes and what it
/// reads off it (lot "Carnet shelf & entries").
@MainActor
final class ShelfEntriesParity: XCTestCase {
    func testShelfDecode() async {
        await Parity.run("carnet", "shelf-decode") { c in
            do {
                let r = try JSONDecoder().decode(ShelfResponse.self, from: c.data())
                return [
                    "garden": ["username": r.garden.username, "site": r.garden.site as Any],
                    "kinds": r.kinds.map { ["kind": $0.kind.rawValue, "label": $0.kind.label, "count": $0.count] },
                    "entries": r.entries.map(ShelfObserve.entry),
                ]
            } catch {
                return ["failed": ShelfFixture.failure(error)]
            }
        }
    }

    /// GET the list, the archived half, every note, one entry's page.
    func testShelfReads() async {
        await Parity.run("carnet", "shelf-reads") { c in
            ShelfFixture.session()
            return await ShelfFixture.exchange {
                switch c.string("call") {
                case "shelf":
                    let r = try await GardenAPI.shelf(archived: c.bool("archived") ?? false)
                    return ["username": r.garden.username, "site": r.garden.site as Any,
                            "entries": r.entries.map(\.id), "kinds": r.kinds.map { "\($0.kind.rawValue)=\($0.count)" }]
                case "notes":
                    let r = try await GardenAPI.notes()
                    return ["entries": r.entries.map(\.id),
                            "notes": r.notes.map { ["id": $0.id, "entryId": $0.entryId, "item": ShelfObserve.item($0.item)] }]
                case "entry":
                    let page = try await GardenAPI.entryPage(try ShelfFixture.entry(c))
                    return page.map(ShelfObserve.written) as Any
                case "entryNamed":
                    let page = try await GardenAPI.entryPage(collection: c.string("collection")!, locale: c.string("locale")!, slug: c.string("slug")!)
                    return ShelfObserve.written(page)
                case "conversations":
                    return try await GardenAPI.conversations(about: try ShelfFixture.entry(c)).map(ShelfObserve.conversation)
                case "deploy":
                    let d = try await GardenAPI.deployState()
                    return ["status": d.status, "error": d.error as Any, "inProgress": d.inProgress, "failed": d.failed]
                default:
                    return "unknown call"
                }
            }
        }
    }

    /// Every write on an entry: a note, the card, publishing, archiving,
    /// deleting, sharing with a member, the details, a summary.
    func testShelfWrites() async {
        await Parity.run("carnet", "shelf-writes") { c in
            ShelfFixture.session()
            return await ShelfFixture.exchange {
                switch c.string("call") {
                case "addNote":
                    return ShelfObserve.written(try await GardenAPI.addNote(
                        to: try ShelfFixture.entry(c), text: c.string("text") ?? "", quote: c.string("quote"), page: c.string("page")))
                case "addBookNote":
                    try await GardenAPI.addBookNote(bookId: c.int("bookId")!, text: c.string("text") ?? "", chapterTitle: c.string("chapterTitle"))
                    return "done"
                case "writeShared":
                    return ShelfObserve.written(try await GardenAPI.writeShared(
                        try ShelfFixture.entry(c), body: c.string("body") ?? "", title: c.string("title")))
                case "publish":
                    return ShelfObserve.written(try await GardenAPI.setPublished(try ShelfFixture.entry(c), c.bool("on") ?? true))
                case "archive":
                    try await GardenAPI.setArchived(try ShelfFixture.entry(c), c.bool("on") ?? true)
                    return "done"
                case "delete":
                    try await GardenAPI.deleteEntry(try ShelfFixture.entry(c))
                    return "done"
                case "share":
                    return ShelfObserve.written(try await GardenAPI.share(try ShelfFixture.entry(c), with: c.string("member")!))
                case "unshare":
                    let w = try await GardenAPI.unshare(try ShelfFixture.entry(c), memberId: c.string("member")!)
                    return w.map(ShelfObserve.written) as Any
                case "refreshDetails":
                    return ShelfObserve.written(try await GardenAPI.refreshDetails(try ShelfFixture.entry(c)))
                case "summaries":
                    let s = try await GardenAPI.summaries(slug: c.string("slug")!, locale: c.string("locale")!, force: c.bool("force") ?? false)
                    return ["text": s.text, "long": s.long as Any, "current": s.current]
                case "conversationSummary":
                    let s = try await GardenAPI.conversationSummary(try ShelfFixture.entry(c), conversation: c.string("conversation")!,
                                                                     force: c.bool("force") ?? false)
                    return ["id": s.id, "text": s.text, "writtenAt": s.writtenAt as Any, "stale": s.stale]
                case "setArticleDomain":
                    let d = try await CarnetAPI.setArticleDomain(slug: c.string("slug")!, locale: c.string("locale"), domainId: c.string("domain"))
                    return d.map { ["id": $0.id, "name": $0.name, "icon": $0.icon as Any] } as Any
                default:
                    return "unknown call"
                }
            }
        }
    }

    /// A request refused with 401: the member is signed out of this device.
    func testShelfSessionRefused() async {
        await Parity.run("carnet", "shelf-session-refused") { c in
            let s = ShelfFixture.session()
            let ex = await ShelfFixture.exchange { try await GardenAPI.shelf().entries.map(\.id) }
            // The session hears of it on the main queue.
            for _ in 0..<20 { await Task.yield(); try? await Task.sleep(nanoseconds: 10_000_000) }
            return [
                "exchange": ex,
                "signedIn": s.hasActiveSession,
                "activeMember": s.activeMemberId as Any,
                "households": s.households.count,
            ]
        }
    }
}
