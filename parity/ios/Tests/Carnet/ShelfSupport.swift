import Foundation
import Security

/// Carnet's fixture for the parity suite (lot "Carnet shelf & entries"): a
/// blank device paired with the fake household, one member signed in — the
/// way `MauriceFixture` does for Maurice, through Carnet's own `CarnetSession`.
/// The Kotlin side is `ShelfSupport.kt` (`Session` with prefix "carnet").
@MainActor
enum ShelfFixture {
    static let householdName = "Parity"

    /// A fresh CarnetSession on the fake household, `member` signed in with
    /// `token`. Everything Carnet reads its requests from (`CarnetAPI.baseURL`,
    /// `CarnetAPI.authToken`) follows it: the App Group mirror.
    @discardableResult
    static func session(
        member: String = "u1", token: String = "tok-u1",
        displayName: String = "Alice", role: String? = "standard"
    ) -> CarnetSession {
        wipe()
        let s = CarnetSession()
        s.pair(serverURL: Parity.baseURL, name: householdName)
        s.updateDeviceMembers([CarnetAPI.Member(id: member, display_name: displayName, avatar_color: "#6b6460",
                                                has_pin: false, role: role, username: member)])
        s.loginMember(member, token: token)
        // The runner holds no keychain group "group.eu.chezmaurice.carnet.app"
        // (harness entitlements): TokenVault then reads the App Group's
        // UserDefaults, where tokens lived before 21 October 2026.
        if s.tokenForActiveMember != token, let hid = s.currentHouseholdId {
            CarnetAPI.defaults.set(token, forKey: CarnetSession.tokenKey(hid, member))
            CarnetAPI.defaults.set(token, forKey: "carnet.token.\(member)")
        }
        return s
    }

    /// A blank device: no household, no token, no queued capture.
    static func wipe() {
        for d in [CarnetAPI.defaults, UserDefaults.standard] {
            for key in d.dictionaryRepresentation().keys
            where key.hasPrefix("carnet.") || key == "apiBaseURL" {
                d.removeObject(forKey: key)
            }
        }
        SecItemDelete([kSecClass as String: kSecClassGenericPassword] as CFDictionary)
        for item in OfflineQueue.shared.pendingItems() { OfflineQueue.shared.dequeue(item.id) }
    }

    /// A row as the list hands it to an entry's page, from the case's JSON.
    static func entry(_ c: ParityCase, _ key: String = "entry") throws -> ShelfEntry {
        try JSONDecoder().decode(ShelfEntry.self, from: c.data(key))
    }

    /// What the member is told when a call fails (the screens show
    /// `EntryView.message`: the server's `error`, else the raw body). A body
    /// the app cannot read is "undecodable"; a network failure "network".
    static func failure(_ error: Error) -> String {
        switch error {
        case is DecodingError: return "undecodable"
        case is URLError: return "network"
        case is CarnetAPI.APIError: return "server: " + EntryView.message(error)
        default: return "other: \(error)"
        }
    }

    /// `{requests, result}`; a failure is `{"failed": failure(error)}`.
    static func exchange(_ body: () async throws -> Any?) async -> [String: Any] {
        var result: Any
        do { result = Parity.normalise(try await body()) } catch { result = ["failed": failure(error)] }
        return ["requests": await Parity.requests(), "result": result]
    }
}

// MARK: - Observations shared by the domains

enum ShelfObserve {
    static func member(_ m: MemberBrief) -> [String: Any] {
        ["id": m.id, "username": m.username, "displayName": m.displayName]
    }

    /// An entry as a row and a page show it: what the member reads off it.
    static func entry(_ e: ShelfEntry) -> [String: Any] {
        [
            "id": e.id,
            "kind": e.kind.rawValue,
            "kindLabel": e.kind.label,
            "collection": e.collection as Any,
            "locale": e.locale as Any,
            "slug": e.slug as Any,
            "title": e.title,
            "byline": e.byline as Any,
            "kindLine": e.kindLine,
            "coverLetter": e.coverLetter,
            "squareCover": e.kind.squareCover,
            "date": e.date,
            "updatedAt": e.updatedAt,
            "image": e.image as Any,
            "tags": e.tags,
            "rating": e.rating as Any,
            "status": e.status as Any,
            "isLibraryOnly": e.isLibraryOnly,
            "isUntouched": e.isUntouched,
            "noteCount": e.noteCount,
            "opened": e.mine?.opened as Any,
            "isForeign": e.isForeign,
            "isShared": e.isShared,
            "people": e.people.map(member),
            "owner": e.owner.map(member) as Any,
            "archived": e.archived as Any,
            "shared": e.shared?.state.rawValue as Any,
            "translations": e.translations.map { ["id": $0.id, "locale": $0.locale, "shared": $0.shared?.state.rawValue as Any] },
            "source": e.source.map(source) as Any,
        ]
    }

    static func source(_ s: ShelfSource) -> [String: Any] {
        [
            "type": s.type,
            "isBook": s.isBook,
            "isReadable": s.isReadable,
            "bookId": s.bookId as Any,
            "chapters": s.chapters as Any,
            "url": s.url as Any,
            "captured": s.captured as Any,
            "wordCount": s.wordCount as Any,
            "readingMinutes": s.readingMinutes as Any,
            "progress": s.progress.map { [
                "chapterSlug": $0.chapterSlug, "chapterTitle": $0.chapterTitle as Any, "fraction": $0.fraction,
                "finished": $0.finished, "tracking": $0.tracking, "updatedAt": $0.updatedAt,
            ] } as Any,
            "summary": s.summary.map { ["text": $0.text, "long": $0.long as Any, "current": $0.current] } as Any,
            "sharedByUsername": s.sharedBy?.username as Any,
        ]
    }

    static func item(_ i: MineItem) -> [String: Any] {
        [
            "id": i.id,
            "kind": i.kind,
            "date": i.date as Any,
            "text": i.text as Any,
            "quote": i.quote as Any,
            "summary": i.summary as Any,
            "page": i.place?.page as Any,
            "chapterTitle": i.place?.chapterTitle as Any,
            "view": i.place?.view as Any,
            "from": i.from.map { ["label": $0.label, "entryId": $0.entryId as Any, "publishedUrl": $0.publishedUrl as Any] } as Any,
            "author": i.author.map(member) as Any,
            "conversation": i.conversation.map { ["id": $0.id, "initiator": $0.initiator?.id as Any] } as Any,
        ]
    }

    static func detail(_ d: EntryDetail) -> [String: Any] {
        [
            "mine": d.mine.map { m in [
                "notes": m.notes, "opened": m.opened, "webPath": m.webPath as Any, "file": m.file, "prose": m.prose,
                "items": m.items.map(item),
            ] } as Any,
            "shared": d.shared.map { s in [
                "state": s.state.rawValue, "webPath": s.webPath as Any, "file": s.file, "publicUrl": s.publicUrl as Any,
                "title": s.title as Any, "body": s.body,
                "links": s.links.map { ["basename": $0.basename, "label": $0.label, "entryId": $0.entryId as Any, "publishedUrl": $0.publishedUrl as Any] },
            ] } as Any,
        ]
    }

    static func written(_ w: GardenAPI.Written) -> [String: Any] {
        [
            "entry": entry(w.entry),
            "detail": detail(w.detail),
            "deploy": w.deploy.map { ["status": $0.status, "error": $0.error as Any, "inProgress": $0.inProgress, "failed": $0.failed] } as Any,
        ]
    }

    static func conversation(_ c: EntryConversation) -> [String: Any] {
        [
            "id": c.id, "displayTitle": c.displayTitle, "when": c.when, "messageCount": c.messageCount, "via": c.via,
            "maurice": c.maurice?.name as Any,
            "summary": c.summary.map { ["id": $0.id, "text": $0.text, "writtenAt": $0.writtenAt as Any, "stale": $0.stale] } as Any,
        ]
    }

    /// Only the requests to the routes this domain is about (not the
    /// household's background chatter).
    static func requests(prefix: String = "/api/") async -> [Any] {
        (await Parity.requests() as? [[String: Any]] ?? []).filter { ($0["path"] as? String)?.hasPrefix(prefix) == true }
    }
}
