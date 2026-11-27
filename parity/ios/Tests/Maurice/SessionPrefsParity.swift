import XCTest

@MainActor
final class SessionPrefsParity: XCTestCase {
    /// The device report's values are the platform's own (iOS, iPhone…):
    /// only its shape is compared — the fields that carry a value. Not
    /// `app_version`: the parity runner has no bundle version, so iOS sends
    /// none here, where the shipped app does.
    static func summarise(_ log: [[String: Any]]) -> [[String: Any]] {
        log.map { r in
            var out: [String: Any] = ["method": r["method"] ?? NSNull(), "path": r["path"] ?? NSNull(),
                                      "auth": r["auth"] ?? NSNull(), "contentType": r["contentType"] ?? NSNull()]
            if r["path"] as? String == "/api/users/me/device", let body = r["body"] as? [String: Any] {
                let fields = body.filter { !($0.value is NSNull) && $0.key != "app_version" }.keys.sorted()
                out["body"] = ["fields": fields, "app": body["app"] ?? NSNull(),
                               "installIdLength": (body["install_id"] as? String)?.count as Any] as [String: Any]
            } else {
                out["body"] = r["body"] ?? NSNull()
            }
            if r["unmatched"] != nil { out["unmatched"] = r["unmatched"] }
            return out
        }
    }

    func testPrefs() async {
        await Parity.run("maurice", "session-prefs") { c in
            let s = MauriceFixture.session()
            await s.loadPreferences()
            if c.bool("twice") == true { await s.loadPreferences() }
            return [
                "palette": s.palette,
                "locale": s.localeIdentifier as Any,
                "requests": Self.summarise(await SessionSupport.requests()),
            ]
        }
    }

    func testPrefsSave() async {
        await Parity.run("maurice", "session-prefs-save") { c in
            let s = MauriceFixture.session()
            s.palette = c.string("palette") ?? "auto"
            s.localeIdentifier = c.string("locale")
            await s.savePreferences()
            let localeNow = s.localeIdentifier
            // What the device keeps: read back as a fresh launch would.
            let again = SessionStore()
            return [
                "localeBeforeRelaunch": localeNow as Any,
                "palette": again.palette,
                "locale": again.localeIdentifier as Any,
                "requests": await SessionSupport.requests(),
            ]
        }
    }
}
