import XCTest

/// Shared gardens in the sidebar (parity/vectors/maurice/content-gardens.json).
@MainActor
final class ContentGardensParity: XCTestCase {
    func testContentGardens() async {
        await Parity.run("maurice", "content-gardens") { c in
            let session = MauriceFixture.session()
            let store = GardensStore(session: session)
            switch c.string("op") {
            case "load":
                await store.load()
                return ["loaded": store.loaded, "gardens": store.gardens.map(Self.garden), "requests": await Self.requests()]
            case "labels":
                // The device's members as the picker keeps them: the fixture's, and the case's.
                let extra = (c.array("members") ?? []).compactMap { $0 as? [String: Any] }.map {
                    DeviceUser(id: $0["id"] as? String ?? "", displayName: $0["displayName"] as? String ?? "", avatarColor: "#6b6460",
                               hasPin: false, role: $0["role"] as? String, avatarURL: nil, username: nil)
                }
                session.updateDeviceUsers([DeviceUser(id: "u1", displayName: "Alice", avatarColor: "#6b6460",
                                                      hasPin: false, role: "standard", avatarURL: nil, username: nil)] + extra)
                await store.load()
                return ["labels": store.gardens.map { store.label(for: $0) }]
            case "access":
                let a = await store.noteAccess(Self.note(c))
                return ["access": a.map { a -> [String: Any] in
                    ["ownerId": a.owner_id, "slug": a.slug, "title": a.title,
                     "members": a.members.map { ["id": $0.member_id, "name": $0.display_name, "tends": $0.tends, "owner": $0.is_owner, "self": $0.is_self] }]
                } as Any, "requests": await Self.requests()]
            case "share":
                await store.share(Self.note(c), with: c.string("member") ?? "")
                return ["requests": await Self.requests(), "gardens": store.gardens.map(\.id)]
            case "leave":
                await store.leave(Self.note(c))
                return ["requests": await Self.requests(), "gardens": store.gardens.map(\.id)]
            case "theme":
                await store.load()
                let g = store.gardens[c.int("garden") ?? 0]
                await store.setTheme(c.string("theme") ?? "", for: g)
                return ["requests": await Self.requests()]
            default:
                return ["unknown op": c.string("op") as Any]
            }
        }
    }

    static func requests() async -> [[String: Any]] {
        await ChatSupport.log().map { ["method": $0["method"] as Any, "path": $0["path"] as Any, "body": $0["body"] as Any] }
    }

    static func note(_ c: ParityCase) -> ServerGardenNote {
        let data = (try? JSONSerialization.data(withJSONObject: c.dict("note") ?? [:])) ?? Data()
        return try! JSONDecoder().decode(ServerGardenNote.self, from: data)
    }

    static func garden(_ g: ServerGarden) -> [String: Any] {
        ["id": g.id, "mine": g.mine, "members": g.members.map(\.member_id), "webTheme": g.web_theme, "webPath": g.web_path,
         "notes": g.notes.map { ["id": $0.id, "isNote": $0.isNote, "unreviewed": $0.isUnreviewed, "updatedAt": $0.updated_at as Any] }]
    }
}
