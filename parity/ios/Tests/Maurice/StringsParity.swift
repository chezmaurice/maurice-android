import Foundation
import XCTest

/// The app's words: the table each language ships, and how a template with
/// placeholders is filled.
final class StringsParity: XCTestCase {
    func testTable() async {
        await Parity.run("maurice", "strings-table") { c in
            // The table L() reads: Localizable.strings of <lang>.lproj in the
            // app's bundle (here the runner's, which carries the app's lproj).
            guard let url = Bundle.main.url(forResource: "Localizable", withExtension: "strings",
                                             subdirectory: nil, localization: c.string),
                  let table = NSDictionary(contentsOf: url) as? [String: String] else {
                return ["missing": c.string]
            }
            return table
        }
    }

    func testFormat() async {
        await Parity.run("maurice", "strings-format") { c in
            let template = c.string("template") ?? ""
            let args: [CVarArg] = (c.array("args") ?? []).map { a in
                if let s = a as? String { return s }
                let n = a as! NSNumber
                // A JSON number with no fraction is an integer to the app (Int);
                // anything else a Double — what the Swift call sites pass.
                return n.doubleValue == n.doubleValue.rounded() && !"\(n)".contains(".") ? n.intValue : n.doubleValue
            }
            // As L() and SessionStore.localized call it: no arguments, no formatting.
            return args.isEmpty ? template : String(format: template, arguments: args)
        }
    }
}
