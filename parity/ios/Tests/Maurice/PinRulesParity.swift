import XCTest

final class PinRulesParity: XCTestCase {
    func testPinRules() async {
        await Parity.run("maurice", "pin-rules") { c in
            ["tooEasy": PinRules.tooEasy(c.string)]
        }
    }
}
