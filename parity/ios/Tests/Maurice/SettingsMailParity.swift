import XCTest

/// Settings › Mail and Address books (lot Settings): the provider help, and the
/// server's answers as the panes read them.
@MainActor
final class SettingsMailParity: XCTestCase {
    func testSettingsMail() async {
        await Parity.run("maurice", "settings-mail") { c in
            switch c.string("kind") {
            case "mailProvider":
                guard let p = MailProvider.of(c.string("address") ?? "") else { return NSNull() }
                return [
                    "help": p.helpKey,
                    "pageKey": p.page?.key as Any,
                    "pageURL": p.page?.url as Any,
                    "possible": p.possible,
                    "wantsAppPassword": p.wantsAppPassword,
                ]
            case "contactProvider":
                let p = ContactProvider.of(c.string("username") ?? "")
                return [
                    "help": p.helpKey,
                    "pageKey": p.page?.key as Any,
                    "pageURL": p.page?.url as Any,
                    "possible": p.possible,
                    "needsServer": p.needsServer,
                ]
            case "decode":
                switch c.string("type") {
                case "scan":
                    return SettingsSupport.decode(MailScanStatus.self, c).map(Self.scan) ?? "undecodable"
                case "mailAccount":
                    return SettingsSupport.decode(MailAccountRow.self, c).map(Self.mail) ?? "undecodable"
                case "mailAccounts":
                    return SettingsSupport.decode(MailAccountsResponse.self, c).map { $0.accounts.map(Self.mail) } ?? "undecodable"
                case "contactAccount":
                    return SettingsSupport.decode(ContactAccountRow.self, c).map(Self.contact) ?? "undecodable"
                case "contactAccounts":
                    return SettingsSupport.decode(ContactAccountsResponse.self, c).map { $0.accounts.map(Self.contact) } ?? "undecodable"
                default: return "unknown type"
                }
            default:
                return ["unknown kind": c.string("kind") as Any]
            }
        }
    }

    static func mail(_ a: MailAccountRow) -> [String: Any?] {
        ["id": a.id, "address": a.address, "provider": a.provider, "host": a.host, "state": a.state, "lastError": a.last_error]
    }

    static func contact(_ a: ContactAccountRow) -> [String: Any?] {
        ["id": a.id, "url": a.url, "username": a.username, "provider": a.provider, "state": a.state,
         "lastError": a.last_error, "syncedAt": a.synced_at, "cards": a.cards]
    }

    static func scan(_ s: MailScanStatus) -> [String: Any?] {
        [
            "state": s.state, "running": s.running, "messages": s.messages, "seen": s.seen,
            "lastError": s.last_error, "error": s.error,
            "reading": s.reading.map { ["state": $0.state, "decidedAt": $0.decided_at, "years": $0.years] as [String: Any?] },
            "mailboxes": s.mailboxes.map { $0.map { b -> [String: Any?] in
                [
                    "address": b.address, "messages": b.messages, "untriaged": b.untriaged,
                    "reading": ["window": b.reading.window, "toLight": b.reading.to_light, "kept": b.reading.kept,
                                "skipped": b.reading.skipped, "toRead": b.reading.to_read, "read": b.reading.read],
                    "estimate": b.estimate.map { ["toSort": $0.to_sort, "toRead": $0.to_read, "hours": $0.hours,
                                                  "euros": $0.euros, "pending": $0.pending] as [String: Any?] },
                    "senders": b.top_senders.map { $0.map { ["sender": $0.sender, "messages": $0.messages,
                                                             "setAside": $0.set_aside, "ruleDays": $0.rule_days] as [String: Any?] } },
                    "approved": b.approved,
                ]
            } },
            "documents": s.documents.map { ["stage": $0.stage, "done": $0.done, "total": $0.total, "written": $0.written] },
        ]
    }
}
