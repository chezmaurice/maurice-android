import XCTest

/// Settings › Files, Import my conversations, My data (lot Settings).
///
/// The library's and the account events' loads sit in private view functions
/// (FilesLibraryView.load, MyDataView.loadAccountEvents): the case goes
/// through APIClient on the same path, with the same answer type, as they do.
@MainActor
final class SettingsDataParity: XCTestCase {
    /// MyDataView's private AccountEventsAnswer, same shape.
    private struct EventsAnswer: Decodable { let events: [AccountEvent] }

    func testSettingsData() async {
        await Parity.run("maurice", "settings-data") { c in
            switch c.string("kind") {
            case "bytes":
                return formatBytes(c.int("n") ?? 0)
            case "library":
                await Self.serve("/api/files", c)
                let s = MauriceFixture.session()
                let lib: LibraryResponse? = try? await APIClient(baseURL: Parity.baseURL).get("/api/files", token: s.tokenForActiveUser)
                guard let lib else { return "unreadable" }
                return [
                    "folders": lib.folders.map { ["id": $0.id, "parentId": $0.parent_id as Any, "name": $0.name] },
                    "files": lib.files.map { ["id": $0.id, "folderId": $0.folder_id as Any, "name": $0.name, "kind": $0.kind,
                                              "size": formatBytes($0.size_bytes)] },
                    "summary": ["count": lib.summary.count, "size": formatBytes(lib.summary.size_bytes),
                                "quota": formatBytes(lib.summary.quota_bytes)],
                ]
            case "events":
                await Self.serve("/api/me/events", c)
                let s = MauriceFixture.session()
                let a: EventsAnswer? = try? await APIClient(baseURL: Parity.baseURL).get("/api/me/events", token: s.tokenForActiveUser)
                guard let a else { return "unreadable" }
                return a.events.map { ["id": $0.id, "kind": $0.kind, "createdAt": $0.created_at, "seenAt": $0.seen_at as Any] }
            case "imports":
                let summary = await ImportSummary.load(session: MauriceFixture.session())
                var out: [String: Any] = ["requests": await SettingsSupport.requests("/api/import")]
                for p in ImportProvider.allCases {
                    out[p.rawValue] = summary.histories[p].map { h -> [String: Any?] in
                        [
                            "provider": h.provider, "watermark": h.watermark,
                            "history": h.history.map { ["id": $0.id, "provider": $0.provider, "conversations": $0.conversations,
                                                        "messages": $0.messages, "ranAt": $0.ran_at, "status": $0.status] },
                        ]
                    } ?? NSNull()
                }
                return out
            default:
                return ["unknown kind": c.string("kind") as Any]
            }
        }
    }

    /// The household answers the case's `json` (with its `status`) on GET `path`.
    static func serve(_ path: String, _ c: ParityCase) async {
        await Parity.reset([["method": "GET", "path": path, "json": c["json"] ?? NSNull(), "status": c.int("status") ?? 200]])
    }
}
