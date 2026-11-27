import SwiftUI
import UIKit
import XCTest

final class DomainsColoursParity: XCTestCase {
    /// A colour as 0–255 sRGB components and alpha, read through UIColor.
    static func rgba(_ c: Color) -> [String: Any] {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        guard UIColor(c).getRed(&r, green: &g, blue: &b, alpha: &a) else { return ["unreadable": true] }
        func byte(_ x: CGFloat) -> Int { Int((x * 255).rounded()) }
        return ["r": byte(r), "g": byte(g), "b": byte(b), "a": byte(a)]
    }

    func testDomainsColours() async {
        await Parity.run("maurice", "domains-colours") { c in
            if let hex = c.string("hex") { return ["colour": Self.rgba(Color(hex: hex))] }
            let t = MauriceTheme.palette(c.string("palette") ?? "")
            return ["isDark": t.isDark, "bg": Self.rgba(t.bg), "surface": Self.rgba(t.surface), "surfaceAlt": Self.rgba(t.surfaceAlt),
                    "ink": Self.rgba(t.ink), "inkSoft": Self.rgba(t.inkSoft), "inkMute": Self.rgba(t.inkMute),
                    "rule": Self.rgba(t.rule), "ruleHard": Self.rgba(t.ruleHard), "codeBg": Self.rgba(t.codeBg), "codeInk": Self.rgba(t.codeInk)]
        }
    }
}
