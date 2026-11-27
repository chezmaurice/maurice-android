import XCTest

/// « Connect a browser » (90b027d, c7420ef): the device code, the link the
/// extension is given, its state, the time left. `ShelfBrowserParity.kt`.
@MainActor
final class ShelfBrowserParity: XCTestCase {
    func testBrowserConnect() async {
        await Parity.run("carnet", "shelf-browser-connect") { c in
            switch c.string("call") {
            case "remaining":
                let now = Date(timeIntervalSince1970: (c["now"] as? NSNumber)?.doubleValue ?? 0)
                return ["left": AddDeviceSheet.remaining(c.string("expiresAt") ?? "", now: now) as Any]
            case "state":
                ShelfFixture.session()
                let state = await CarnetAPI.deviceCodeState(c.string("code") ?? "")
                return ["requests": await ShelfObserve.requests(), "result": state as Any]
            default:
                ShelfFixture.session()
                return await ShelfFixture.exchange {
                    let code = try await CarnetAPI.deviceCode()
                    let link = Invitation(server: CarnetAPI.baseURL, code: Invitation.normalizedCode(code.code) ?? code.code).link
                    return ["link": link.replacingOccurrences(of: Parity.baseURL, with: "{base}"), "expiresAt": code.expires_at]
                }
            }
        }
    }
}
