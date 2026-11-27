import XCTest

/// Dictation through the household server (lot Settings). Each case has a base
/// path of its own (`/<slug>`): HouseholdTranscription remembers a server's
/// capability for ten minutes, per base URL.
@MainActor
final class SettingsDictationParity: XCTestCase {
    func testSettingsDictation() async {
        await Parity.run("maurice", "settings-dictation") { c in
            let slug = c.string("slug") ?? "d0"
            let household = HouseholdTranscription(baseURL: Parity.baseURL + "/" + slug, token: "tok-u1", preferred: true)
            switch c.string("kind") {
            case "capability":
                let cap = await household.capability()
                return [
                    "requests": await SettingsSupport.requests("/\(slug)/"),
                    // nil: "could not ask" — iOS then lets the device try on its own.
                    "available": cap.map { $0.available as Any } ?? "unknown",
                ]
            case "transcribe":
                let file = FileManager.default.temporaryDirectory.appendingPathComponent("parity-\(slug).m4a")
                try Data("AUDIO".utf8).write(to: file)
                let text = await household.transcribe(file, seconds: (c["seconds"] as? NSNumber)?.doubleValue ?? 0)
                try? FileManager.default.removeItem(at: file)
                return [
                    "requests": await SettingsSupport.requests("/\(slug)/"),
                    // nil: the server could not (iOS then falls back on its preview / the device).
                    "result": text.map { ["text": $0] as Any } ?? ["failed": true],
                ]
            default:
                return ["unknown kind": c.string("kind") as Any]
            }
        }
    }
}
