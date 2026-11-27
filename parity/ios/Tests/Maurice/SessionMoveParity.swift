import CryptoKit
import XCTest

@MainActor
final class SessionMoveParity: XCTestCase {
    func testRecord() async {
        await Parity.run("maurice", "session-move-record") { c in
            HouseholdMove.parse(c.string)
        }
    }

    func testBytes() async {
        await Parity.run("maurice", "session-move-bytes") { c in
            if let txt = c.string("txt") {
                return ["txt": HouseholdMove.decodeTXT(SessionSupport.hex(txt))]
            }
            return ["bytes": Data(base64URL: c.string("base64url") ?? "").map(SessionSupport.hexString) as Any]
        }
    }

    /// The check `HouseholdMove.proves` makes once the proof is in hand.
    func testEd25519() async {
        await Parity.run("maurice", "session-ed25519") { c in
            let msg: Data
            if let text = c.string("text") { msg = Data(text.utf8) }
            else if let r = c.dict("repeat") { msg = Data(String(repeating: r["text"] as? String ?? "", count: (r["times"] as? Int) ?? 0).utf8) }
            else { msg = SessionSupport.hex(c.string("msg") ?? "") }
            guard let key = try? Curve25519.Signing.PublicKey(rawRepresentation: SessionSupport.hex(c.string("pk") ?? "")) else {
                return ["valid": false]
            }
            return ["valid": key.isValidSignature(SessionSupport.hex(c.string("sig") ?? ""), for: msg)]
        }
    }

    func testProof() async {
        await Parity.run("maurice", "session-move-proof") { c in
            let key = c.string("key") ?? ""
            let result: Any
            if c.bool("follow") == true {
                result = await HouseholdMove.follow(from: Parity.baseURL, key: key) as Any
            } else {
                result = await HouseholdMove.proves(Parity.baseURL, key: key)
            }
            let log = await SessionSupport.log()
            return [
                "result": result,
                "requests": log.map { r in [
                    "method": r["method"] ?? NSNull(),
                    "path": r["path"] ?? NSNull(),
                    "auth": r["auth"] ?? NSNull(),
                    "proofLength": ((r["query"] as? [String: Any])?["proof"] as? String)?.count as Any,
                ] as [String: Any] },
            ]
        }
    }
}
