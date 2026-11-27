import Foundation
import Security

/// The Carnet reading lot's fixture: a blank device paired with the fake
/// household, one member signed in — the iOS side of `ReadingSupport.kt`.
/// Goes through CarnetSession as the app does (pair, members, login); the
/// flat mirror it keeps is what CalibreAPI / GardenAPI / MemberStore read.
@MainActor
enum ReadingFixture {
    static let householdName = "Parity"

    /// A fresh CarnetSession on the fake household, `member` signed in.
    @discardableResult
    static func session(member: String = "u1", token: String = "tok-u1",
                        others: [String] = ["u2"]) -> CarnetSession {
        wipe()
        let s = CarnetSession()
        s.pair(serverURL: Parity.baseURL, name: householdName)
        s.updateDeviceMembers(([member] + others).map {
            CarnetAPI.Member(id: $0, display_name: $0.uppercased(), avatar_color: "#6b6460", has_pin: false, role: "standard")
        })
        signIn(s, member: member, token: token)
        return s
    }

    /// Signs `member` in on the current household (the picker's gesture).
    static func signIn(_ s: CarnetSession, member: String, token: String? = nil) {
        let t = token ?? "tok-\(member)"
        s.loginMember(member, token: t)
        // The runner carries no keychain access group for Carnet's app group:
        // TokenVault's keychain write fails there and it falls back to the
        // App Group's UserDefaults on read. Put the token where it then looks.
        if CarnetAPI.authToken != t {
            CarnetAPI.defaults.set(t, forKey: "carnet.token.\(member)")
            if let hid = s.currentHouseholdId {
                CarnetAPI.defaults.set(t, forKey: CarnetSession.tokenKey(hid, member))
            }
        }
    }

    /// Another household on the same device (pairs and makes it current).
    static func pairOther(_ s: CarnetSession, url: String, member: String = "u1") {
        s.pair(serverURL: url, name: "Other")
        s.updateDeviceMembers([CarnetAPI.Member(id: member, display_name: "A", avatar_color: "#6b6460", has_pin: false, role: "standard")])
        signIn(s, member: member)
    }

    /// A blank device: no household, no token, nothing kept on disk.
    static func wipe() {
        let groups = [CarnetAPI.defaults, UserDefaults.standard]
        CarnetAPI.defaults.removePersistentDomain(forName: CarnetAPI.appGroupID)
        if let id = Bundle.main.bundleIdentifier { UserDefaults.standard.removePersistentDomain(forName: id) }
        for d in groups {
            for key in d.dictionaryRepresentation().keys where key.hasPrefix("carnet.") || key == "apiBaseURL" {
                d.removeObject(forKey: key)
            }
        }
        SecItemDelete([kSecClass as String: kSecClassGenericPassword] as CFDictionary)
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        for dir in ["Members", "OfflineBooks"] {
            let url = support.appendingPathComponent(dir, isDirectory: true)
            if let items = try? FileManager.default.contentsOfDirectory(at: url, includingPropertiesForKeys: nil) {
                for item in items { try? FileManager.default.removeItem(at: item) }
            }
        }
    }
}

enum ReadingObs {
    /// The requests the household received, and what came of the call; a
    /// failure is `{"thrown": true}` on both sides (iOS's errors carry no
    /// status, so the harness's `http NNN` cannot be compared).
    static func exchange(_ body: () async throws -> Any?) async -> [String: Any] {
        var result: Any
        do { result = Parity.normalise(try await body()) } catch { result = ["thrown": true] }
        return ["requests": await Parity.requests(), "result": result]
    }

    static func date(_ iso: String?) -> Date? {
        guard let iso else { return nil }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        if let d = f.date(from: iso) { return d }
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.date(from: iso)
    }

    /// Epoch seconds, or "now" for a stamp the code took from the clock.
    static func stamp(_ d: Date) -> Any {
        abs(d.timeIntervalSinceNow) < 120 ? "now" : d.timeIntervalSince1970
    }

    static func record(_ r: ReadingPositionStore.Record?) -> Any {
        guard let r else { return NSNull() }
        return ["chapterIndex": r.chapterIndex, "chapterSlug": r.chapterSlug, "lastView": r.lastView,
                "fullChar": r.fullChar, "summaryPara": r.summaryPara, "updatedAt": stamp(r.updatedAt)]
    }

    static func chapter(_ c: Chapter) -> [String: Any] {
        ["index": c.index, "slug": c.slug, "title": c.title, "wordCount": c.wordCount as Any,
         "summaryWordCount": c.summaryWordCount as Any, "hasSummary": c.hasSummary, "readingMinutes": c.readingMinutes]
    }

    static func highlight(_ h: Highlight) -> [String: Any] {
        ["id": h.id, "chapterSlug": h.chapterSlug, "quote": h.quote, "note": h.note as Any, "color": h.color,
         "start": h.startOffset as Any, "end": h.endOffset as Any, "readerView": h.readerView]
    }

    static func progress(_ p: ReadingProgress?) -> Any {
        guard let p else { return NSNull() }
        return ["bookId": p.bookId as Any, "chapterIndex": p.chapterIndex as Any, "chapterSlug": p.chapterSlug as Any,
                "view": p.view as Any, "enabled": p.enabled as Any, "position": p.position as Any, "updatedAt": p.updatedAt as Any]
    }

    static func article(_ a: Article) -> [String: Any] {
        ["slug": a.slug, "locale": a.locale, "title": a.title, "id": a.id, "url": a.url as Any,
         "hasText": a.hasText as Any, "textRev": a.textRev as Any, "readableSummary": a.readableSummary,
         "hasSummary": a.hasSummary, "readingMinutes": a.readingMinutes, "bylineShort": a.bylineShort]
    }

    static func target(_ t: LinkTarget) -> [String: Any] {
        ["collection": t.collection, "locale": t.locale, "slug": t.slug, "title": t.title, "date": t.date,
         "linkBasename": t.linkBasename, "hasFiche": t.hasFiche]
    }

    static func decode<T: Decodable>(_ type: T.Type, _ c: ParityCase, _ key: String) throws -> T {
        try JSONDecoder().decode(T.self, from: c.data(key))
    }

    static func round3(_ v: Double) -> Double { (v * 1000).rounded() / 1000 }
}
