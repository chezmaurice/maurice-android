import XCTest

@MainActor
final class ReadingPositionsParity: XCTestCase {
    static let book = 7

    static func rec(_ d: [String: Any]) -> ReadingPositionStore.Record {
        ReadingPositionStore.Record(
            chapterIndex: (d["chapterIndex"] as? NSNumber)?.intValue ?? 0,
            chapterSlug: d["chapterSlug"] as? String ?? "",
            lastView: d["lastView"] as? String ?? "full",
            fullChar: (d["fullChar"] as? NSNumber)?.intValue ?? 0,
            summaryPara: (d["summaryPara"] as? NSNumber)?.intValue ?? 0,
            updatedAt: ReadingObs.date(d["updatedAt"] as? String) ?? Date())
    }

    func testReadingPositions() async {
        await Parity.run("carnet", "reading-positions") { c in
            let s = ReadingFixture.session()
            let book = Self.book
            switch c.string("op") {
            case "reconcile":
                if let local = c.dict("local") { ReadingPositionStore.save(bookId: book, Self.rec(local)) }
                // The household's row, served as the book page reads it.
                let raw: String
                if let r = c.string("raw") { raw = r }
                else if let srv = c["server"], !(srv is NSNull) {
                    raw = String(data: try JSONSerialization.data(withJSONObject: srv), encoding: .utf8)!
                } else { raw = "null" }
                await Parity.reset([["method": "GET", "path": "/api/v1/calibre/books/\(book)/reading-progress",
                                     "status": c.int("status") ?? 200, "text": raw,
                                     "headers": ["content-type": "application/json"]]])
                let progress = try? await CalibreAPI.readingProgress(bookId: book)
                let restored = ReadingPositionStore.reconcile(bookId: book, with: progress)
                return ["restored": ReadingObs.record(restored),
                        "stored": ReadingObs.record(ReadingPositionStore.get(bookId: book))]
            case "members":
                ReadingPositionStore.save(bookId: book, Self.rec(c.dict("save")!))
                let first = ReadingObs.record(ReadingPositionStore.get(bookId: book))
                ReadingFixture.signIn(s, member: "u2")
                let other = ReadingObs.record(ReadingPositionStore.get(bookId: book))
                ReadingFixture.signIn(s, member: "u1")
                let back = ReadingObs.record(ReadingPositionStore.get(bookId: book))
                ReadingFixture.pairOther(s, url: Parity.baseURL + "/other")
                let otherHousehold = ReadingObs.record(ReadingPositionStore.get(bookId: book))
                return ["u1": first, "u2": other, "u1Again": back, "otherHousehold": otherHousehold]
            case "clear":
                ReadingPositionStore.save(bookId: book, Self.rec(c.dict("save")!))
                ReadingPositionStore.save(bookId: 8, Self.rec(c.dict("save")!))
                ReadingPositionStore.clear(bookId: book)
                return ["cleared": ReadingObs.record(ReadingPositionStore.get(bookId: book)),
                        "other": ReadingObs.record(ReadingPositionStore.get(bookId: 8))]
            case "paused":
                var out: [String: Any] = ["initially": ReadingPositionStore.isPaused(bookId: book)]
                ReadingPositionStore.setPaused(true, bookId: book)
                out["paused"] = ReadingPositionStore.isPaused(bookId: book)
                out["otherBook"] = ReadingPositionStore.isPaused(bookId: 8)
                ReadingFixture.signIn(s, member: "u2")
                out["otherMember"] = ReadingPositionStore.isPaused(bookId: book)
                ReadingFixture.signIn(s, member: "u1")
                out["back"] = ReadingPositionStore.isPaused(bookId: book)
                ReadingPositionStore.setPaused(false, bookId: book)
                out["resumed"] = ReadingPositionStore.isPaused(bookId: book)
                return out
            case "article":
                ReadingPositionStore.save(key: c.string("key")!, Self.rec(c.dict("save")!))
                return ["article": ReadingObs.record(ReadingPositionStore.get(key: c.string("key")!)),
                        "book0": ReadingObs.record(ReadingPositionStore.get(bookId: 0))]
            default:
                return ["unknown op": c.string("op") as Any]
            }
        }
    }
}
