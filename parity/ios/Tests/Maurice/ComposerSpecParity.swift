import XCTest

final class ComposerSpecParity: XCTestCase {
    func testComposerSpec() async {
        await Parity.run("maurice", "composer-spec") { c in
            guard let d = c.input as? [String: Any], let it = ComposerStore.itemFromSpec(d) else { return ["item": NSNull()] }
            return ["item": ComposerSupport.item(it)]
        }
    }
}
