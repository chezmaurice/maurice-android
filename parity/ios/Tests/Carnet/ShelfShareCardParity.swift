import XCTest

/// The card handed to the system share sheet (lot "Carnet shelf & entries").
/// What the sheet receives: the card as text, and the address it is read at
/// (a separate item on iOS).
final class ShelfShareCardParity: XCTestCase {
    func testShareCard() async {
        await Parity.run("carnet", "shelf-share-card") { c in
            let entry = try JSONDecoder().decode(ShelfEntry.self, from: c.data("entry"))
            let card = try JSONDecoder().decode(EntryDetail.Shared.self, from: c.data("card"))
            return [
                "text": CardText.text(entry: entry, card: card),
                "link": CardText.link(entry: entry, card: card)?.absoluteString as Any,
                "hasText": CardText.hasText(card),
            ]
        }
    }
}
