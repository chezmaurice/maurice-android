import Foundation
import XCTest

/// The pairings the Maurice app and Carnet share: joined the first time.
final class SessionSharedPairingsParity: XCTestCase {
    func testAbsorb() async {
        await Parity.run("maurice", "session-shared-pairings") { c in
            let decoder = JSONDecoder()
            func pairings(_ key: String) throws -> SharedPairings {
                var d = c.dict(key) ?? [:]
                // The Swift type spells every field; the vectors leave empty ones out.
                d["households"] = (d["households"] as? [[String: Any]] ?? []).map { h -> [String: Any] in
                    var h = h
                    h["members"] = (h["members"] as? [[String: Any]] ?? [])
                    h["tokens"] = h["tokens"] ?? [String: String]()
                    return h
                }
                return try decoder.decode(SharedPairings.self, from: JSONSerialization.data(withJSONObject: d))
            }
            var mine = try pairings("mine")
            mine.absorb(try pairings("theirs"))
            return [
                "current": mine.current as Any,
                "households": mine.households.map { h -> [String: Any] in
                    ["server": h.server, "key": h.key as Any, "name": h.name,
                     "members": h.members.map { ["id": $0.id, "name": $0.display_name] },
                     "active": h.active as Any, "tokens": h.tokens]
                },
            ]
        }
    }
}
