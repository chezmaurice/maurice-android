import XCTest

/// The shelf's « + » (01c3d4e, 6e024c9, ed512a3): words looked up with no
/// conversation, a row kept, a book sent to the library. `ShelfAddParity.kt`.
@MainActor
final class ShelfAddParity: XCTestCase {
    private static func row(_ s: Suggestion) -> [String: Any] {
        [
            "id": s.id, "kind": s.kind, "title": s.title, "year": s.year as Any, "subtitle": s.subtitle as Any,
            "image": s.image as Any, "note": s.note, "state": s.state, "existing": s.existing,
            "candidates": s.candidates.map { ["id": $0.id, "title": $0.title, "year": $0.year as Any, "subtitle": $0.subtitle as Any] },
            "entry": s.entry as Any, "searchable": s.searchable as Any, "isKept": s.isKept,
        ]
    }

    private static func list(_ l: SuggestionList) -> [String: Any] {
        ["batch": l.batch as Any, "rows": l.suggestions.map(row), "pending": l.pending, "kept": l.kept]
    }

    /// The multipart body carries a boundary of each side's own: what is
    /// compared of a file sent is where it went and how.
    private static func requests(file: Bool) async -> [Any] {
        let all = await ShelfObserve.requests()
        guard file else { return all }
        return all.map { r in
            guard var r = r as? [String: Any] else { return r }
            r["body"] = nil
            return r
        }
    }

    func testAddEntry() async {
        await Parity.run("carnet", "shelf-add-entry") { c in
            ShelfFixture.session()
            let call = c.string("call") ?? ""
            var result: Any
            do {
                switch call {
                case "ask":
                    result = Self.list(try await MauriceAPI.suggestions(fromText: c.string("text") ?? "", locale: c.string("locale")))
                case "reread":
                    result = Self.list(try await MauriceAPI.suggestions(batch: c.string("batch") ?? ""))
                case "keep":
                    let kept = try await MauriceAPI.keep(c.string("id") ?? "", candidate: c.string("candidate"))
                    result = ["open": kept?.entry as Any]
                case "addFile":
                    let added = try await CarnetAPI.addBookToCalibre(data: Data((c.string("bytes") ?? "").utf8), filename: c.string("filename") ?? "")
                    result = ["open": added.bookId.map { "calibre/\($0)" } as Any, "alreadyThere": added.alreadyThere]
                default:
                    result = "unknown call"
                }
            } catch let e as CarnetAPI.LibraryUnavailable {
                result = ["failed": "unavailable: " + e.message]
            } catch {
                result = ["failed": ShelfFixture.failure(error)]
            }
            return ["requests": await Self.requests(file: call == "addFile"), "result": result]
        }
    }
}
