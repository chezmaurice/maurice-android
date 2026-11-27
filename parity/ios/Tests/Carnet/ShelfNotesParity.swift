import XCTest

/// Rewriting or taking off a note (896f705): the request, the entry read
/// again, what the member is told; what a note says of itself.
@MainActor
final class ShelfNotesParity: XCTestCase {
    private final class Count { var n = 0 }

    func testNoteEdit() async {
        await Parity.run("carnet", "shelf-note-edit") { c in
            let item: MineItem
            do { item = try JSONDecoder().decode(MineItem.self, from: c.data("item")) }
            catch { return ["failed": ShelfFixture.failure(error)] }
            if c.string("call") == "read" {
                return [
                    "canEdit": item.canEdit, "canDelete": item.canDelete, "edited": item.edited as Any,
                    "editedLine": item.edited.flatMap { $0.isEmpty ? nil : NoteEdits.editedLine($0) } as Any,
                ]
            }
            ShelfFixture.session()
            let target = NoteEditTarget(entry: try ShelfFixture.entry(c), item: item)
            let reloads = Count()
            var result: Any = "done"
            do {
                switch c.string("call") {
                case "edit":
                    try await NoteEdits.save(target, text: c.string("text") ?? "", quote: c.string("quote"), page: c.string("page")) { reloads.n += 1 }
                case "delete":
                    try await NoteEdits.delete(target) { reloads.n += 1 }
                default: break
                }
            } catch {
                result = ["failed": NoteEdits.message(error)]
            }
            return ["requests": await ShelfObserve.requests(), "result": result, "reloads": reloads.n]
        }
    }
}
