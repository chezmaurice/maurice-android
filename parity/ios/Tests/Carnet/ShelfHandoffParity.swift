import XCTest

/// Talking an entry over with Maurice (lot "Carnet shelf & entries"): the
/// conversation opened from the entry — the request, the id the Maurice app
/// is then opened on, or what the member is told.
@MainActor
final class ShelfHandoffParity: XCTestCase {
    func testHandoff() async {
        await Parity.run("carnet", "shelf-handoff") { c in
            ShelfFixture.session()
            return await ShelfFixture.exchange {
                ["conversation": try await MauriceAPI.open(from: c.string("entry") ?? "")]
            }
        }
    }
}
