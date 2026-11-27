import Foundation

/// Helpers of the Session lot (invitations, pairing, sign-in, preferences,
/// consent, budget, account events) — the Swift side of SessionSupport.kt.
@MainActor
enum SessionSupport {
    /// `{base}` → the fake household; `{localhost}` → the same, spelt otherwise.
    static func sub(_ s: String) -> String {
        s.replacingOccurrences(of: "{base}", with: Parity.baseURL)
            .replacingOccurrences(of: "{localhost}", with: "http://localhost:\(Parity.port)")
    }

    /// The reverse, so goldens do not depend on the port.
    static func unsub(_ s: String) -> String {
        s.replacingOccurrences(of: Parity.baseURL, with: "{base}")
            .replacingOccurrences(of: "http://localhost:\(Parity.port)", with: "{localhost}")
    }

    /// What iOS leaves running on its own (`Task { … }` after a sign-out, a
    /// receive, a 401 notice delivered on the main queue) is given time to land.
    static func settle(_ seconds: Double = 0.5) async {
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }

    /// The household the device is on, as the switcher shows it.
    static func household(_ s: SessionStore) -> Any {
        guard let h = s.currentHousehold else { return NSNull() }
        return [
            "address": unsub(h.serverURL),
            "name": unsub(h.name),
            "color": h.color as Any,
            "icon": h.icon as Any,
            "key": h.key as Any,
            "unread": h.unread as Any,
        ] as [String: Any]
    }

    /// The fake household's log, less the live socket's reconnections
    /// (`/api/me/ws`), which a ChatService left by another test keeps making.
    static func log() async -> [[String: Any]] {
        (await Parity.requests() as? [[String: Any]] ?? []).filter { ($0["path"] as? String) != "/api/me/ws" }
    }

    /// The fake household's log, each request as the server saw it.
    static func requests(sorted: Bool = false) async -> [[String: Any]] {
        var log = (await SessionSupport.log()).map { r -> [String: Any] in
            var r = r
            r.removeValue(forKey: "query")
            return r
        }
        if sorted {
            log.sort { a, b in
                let ka = "\(a["method"] ?? "") \(a["path"] ?? "") \(a["auth"] ?? "")"
                let kb = "\(b["method"] ?? "") \(b["path"] ?? "") \(b["auth"] ?? "")"
                return ka < kb
            }
        }
        return log
    }

    /// The distinct requests, without bodies: for flows where iOS fires the
    /// same request several times concurrently.
    static func distinctRequests() async -> [String] {
        let log = await SessionSupport.log()
        return Array(Set(log.map { r in
            "\(r["method"] ?? "") \(r["path"] ?? "") \((r["auth"] as? String) ?? "-")\((r["unmatched"] as? Bool) == true ? " unmatched" : "")"
        })).sorted()
    }

    static func hex(_ s: String) -> Data {
        var out = Data()
        var i = s.startIndex
        while i < s.endIndex, let j = s.index(i, offsetBy: 2, limitedBy: s.endIndex) {
            out.append(UInt8(s[i..<j], radix: 16) ?? 0)
            i = j
        }
        return out
    }

    static func hexString(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
}
