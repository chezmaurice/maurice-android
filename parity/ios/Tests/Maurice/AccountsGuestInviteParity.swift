import XCTest

/// A friend from another household invited as a guest
/// (parity/vectors/maurice/accounts-guest-invite.json). Each case uses a
/// member of its own: the refusals are kept for the app's run.
@MainActor
final class AccountsGuestInviteParity: XCTestCase {
    func testGuestInvite() async {
        await Parity.run("maurice", "accounts-guest-invite") { c in
            let member = c.string("member") ?? ""
            let outcome = await GuestInvite.create(server: Parity.baseURL, member: member, token: "tok-u1")
            var result: [String: Any]
            switch outcome {
            case .code(let code):
                let i = code.invitation(server: Parity.baseURL)
                result = ["kind": "code", "code": code.code, "expires_at": code.expires_at,
                          "invitation": ["server": Self.port(i.server), "code": i.code, "link": Self.port(i.link)]]
            case .notAllowed:
                result = ["kind": "not_allowed"]
            case .failed(let message):
                result = ["kind": "failed", "message": message]
            }
            var refused: [String: Any] = ["self": GuestInvite.isRefused(server: Parity.baseURL, member: member)]
            if let also = c.string("also") { refused["also"] = GuestInvite.isRefused(server: Parity.baseURL, member: also) }
            return ["requests": await Parity.requests(), "result": result, "refused": refused]
        }
    }

    /// The fake household's address, whatever port this run gave it.
    static func port(_ s: String) -> String { s.replacingOccurrences(of: Parity.baseURL, with: "<household>") }
}
