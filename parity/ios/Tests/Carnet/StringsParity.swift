import Foundation
import XCTest

/// Carnet's words: the table each language ships.
final class StringsParity: XCTestCase {
    func testTable() async {
        await Parity.run("carnet", "strings-table") { c in
            // The table L() reads: Localizable.xcstrings, compiled into the
            // Localizable.strings of <lang>.lproj in the app's bundle (here
            // the runner's, which carries the app's catalogue).
            guard let url = Bundle.main.url(forResource: "Localizable", withExtension: "strings",
                                             subdirectory: nil, localization: c.string),
                  let table = NSDictionary(contentsOf: url) as? [String: String] else {
                return ["missing": c.string]
            }
            return table
        }
    }
}
