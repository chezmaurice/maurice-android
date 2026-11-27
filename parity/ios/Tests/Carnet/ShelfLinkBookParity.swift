import XCTest

/// Tying a book entry to a library book (3d2bf6b): the library read, the
/// book written on the fiche. `ShelfLinkBookParity.kt`.
@MainActor
final class ShelfLinkBookParity: XCTestCase {
    func testLinkBook() async {
        await Parity.run("carnet", "shelf-link-book") { c in
            ShelfFixture.session()
            return await ShelfFixture.exchange {
                switch c.string("call") {
                case "books":
                    return try await CalibreAPI.books().map { ["id": $0.id, "title": $0.title, "author": $0.author, "coverLetter": $0.coverLetter] }
                case "link":
                    return ShelfObserve.written(try await GardenAPI.linkBook(try ShelfFixture.entry(c), bookId: c.int("bookId") ?? 0))
                default:
                    return "unknown call"
                }
            }
        }
    }
}
