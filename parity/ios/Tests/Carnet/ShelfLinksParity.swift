import XCTest

/// An entry's page on the garden website, opened signed in from the entry's
/// ⋯ menu (lot "Carnet shelf & entries"): the token asked for, the address opened.
@MainActor
final class ShelfLinksParity: XCTestCase {
    func testWebLink() async {
        await Parity.run("carnet", "shelf-web-link") { c in
            ShelfFixture.session()
            var opened: [Any] = []
            for _ in 0..<(c.int("times") ?? 1) {
                opened.append(await GardenLinks.webURL(c.string("path") ?? "")?.absoluteString.replacingOccurrences(of: Parity.baseURL, with: "{base}") as Any)
            }
            return ["opened": opened, "requests": await ShelfObserve.requests()]
        }
    }
}
