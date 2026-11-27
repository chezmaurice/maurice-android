import Foundation
import XCTest

/// The iOS half of the parity suite. Each test class reads the cases of one
/// domain (`parity/vectors/<app>/<domain>.json`), runs every case through the
/// iOS app's own Swift code, and writes what it observed to
/// `parity/golden/<app>/<domain>.json`. The Kotlin half (:parity) runs the
/// same cases through the Android code and compares against that file: the
/// iOS app is the reference.
///
/// An observation is plain JSON (dictionaries, arrays, strings, numbers,
/// booleans, NSNull) shaped the same way on both sides by the adapters.
struct ParityCase {
    let name: String
    let input: Any
    let raw: [String: Any]

    subscript(_ key: String) -> Any? { (input as? [String: Any])?[key] }
    var string: String { input as? String ?? "" }
    func string(_ key: String) -> String? { self[key] as? String }
    func int(_ key: String) -> Int? { (self[key] as? NSNumber)?.intValue }
    func bool(_ key: String) -> Bool? { (self[key] as? NSNumber)?.boolValue }
    func dict(_ key: String) -> [String: Any]? { self[key] as? [String: Any] }
    func array(_ key: String) -> [Any]? { self[key] as? [Any] }
    /// A sub-value re-encoded as JSON data, to feed a Decodable.
    func data(_ key: String? = nil) -> Data {
        let v: Any = key.map { self[$0] ?? NSNull() } ?? input
        if let s = v as? String { return Data(s.utf8) }
        return (try? JSONSerialization.data(withJSONObject: v, options: [.fragmentsAllowed])) ?? Data()
    }
}

enum Parity {
    /// `parity/`, found from this file's place in the repo.
    static let root: URL = {
        if let env = ProcessInfo.processInfo.environment["PARITY_ROOT"] { return URL(fileURLWithPath: env) }
        return URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
    }()

    static let port = ProcessInfo.processInfo.environment["PARITY_PORT"] ?? "47800"
    /// The fake household (parity/server/fake-household.ts).
    static var baseURL: String { "http://127.0.0.1:\(port)" }

    /// Runs every case of a domain and writes the golden file. A case whose
    /// body throws is recorded as `{"thrown": "<description>"}`.
    static func run(
        _ app: String, _ domain: String,
        file: StaticString = #filePath, line: UInt = #line,
        _ body: (ParityCase) async throws -> Any?
    ) async {
        let vectorURL = root.appendingPathComponent("vectors/\(app)/\(domain).json")
        guard let data = try? Data(contentsOf: vectorURL),
              let doc = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let cases = doc["cases"] as? [[String: Any]] else {
            XCTFail("unreadable vectors: \(vectorURL.path)", file: file, line: line)
            return
        }
        var results: [String: Any] = [:]
        var names = Set<String>()
        for c in cases {
            guard let name = c["name"] as? String else { continue }
            if !names.insert(name).inserted { XCTFail("duplicate case \(domain)/\(name)", file: file, line: line) }
            let pc = ParityCase(name: name, input: c["input"] ?? NSNull(), raw: c)
            if let routes = c["routes"] { await reset(routes) }
            do {
                results[name] = normalise(try await body(pc))
            } catch {
                results[name] = ["thrown": String(describing: error)]
            }
        }
        let out: [String: Any] = [
            "domain": domain,
            "source": sourceStamp(app),
            "results": results,
        ]
        let goldenURL = root.appendingPathComponent("golden/\(app)/\(domain).json")
        try? FileManager.default.createDirectory(at: goldenURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        do {
            let json = try JSONSerialization.data(withJSONObject: out, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
            try (json + Data("\n".utf8)).write(to: goldenURL)
        } catch {
            XCTFail("cannot write \(goldenURL.path): \(error)", file: file, line: line)
        }
    }

    /// Optionals, Dates, URLs and nested values made JSON-safe.
    static func normalise(_ value: Any?) -> Any {
        guard let value else { return NSNull() }
        let mirror = Mirror(reflecting: value)
        if mirror.displayStyle == .optional {
            guard let child = mirror.children.first else { return NSNull() }
            return normalise(child.value)
        }
        switch value {
        case let v as NSNull: return v
        case let v as String: return v
        case let v as Bool where type(of: value) == Bool.self: return v
        case let v as Int where type(of: value) == Int.self: return v
        case let v as Double where type(of: value) == Double.self: return v.isFinite ? v : String(describing: v)
        case let v as NSNumber:
            if CFGetTypeID(v) == CFBooleanGetTypeID() { return v.boolValue }
            return v
        case let v as Date: return iso(v)
        case let v as URL: return v.absoluteString
        case let v as [String: Any?]: return v.mapValues { normalise($0) }
        case let v as [Any?]: return v.map { normalise($0) }
        default:
            if mirror.displayStyle == .enum { return String(describing: value) }
            return String(describing: value)
        }
    }

    static func iso(_ d: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        f.timeZone = TimeZone(identifier: "UTC")
        return f.string(from: d)
    }

    /// The repo and commit the iOS code was read at.
    static func sourceStamp(_ app: String) -> [String: Any] {
        let env = ProcessInfo.processInfo.environment
        return [
            "repo": app == "carnet" ? "carnet" : "maurice",
            "commit": (app == "carnet" ? env["PARITY_CARNET_COMMIT"] : env["PARITY_MAURICE_COMMIT"]) ?? "unknown",
        ]
    }

    // MARK: - The fake household

    static func reset(_ routes: Any) async {
        var req = URLRequest(url: URL(string: baseURL + "/__parity/reset")!)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: ["routes": routes])
        _ = try? await URLSession.shared.data(for: req)
    }

    /// The requests the household received since the last reset.
    static func requests() async -> Any {
        guard let (data, _) = try? await URLSession.shared.data(from: URL(string: baseURL + "/__parity/log")!),
              let json = try? JSONSerialization.jsonObject(with: data) else { return ["unreachable"] }
        return json
    }

    /// The observation every network case returns: what was sent, what came of it.
    static func exchange(_ body: () async throws -> Any?) async -> [String: Any] {
        var result: Any
        do { result = normalise(try await body()) } catch { result = ["thrown": errorKind(error)] }
        return ["requests": await requests(), "result": result]
    }

    /// An error reduced to what both platforms can say: the HTTP status if
    /// there is one, else "error".
    static func errorKind(_ error: Error) -> String {
        let s = String(describing: error)
        if let r = s.range(of: #"\b[1-5]\d\d\b"#, options: .regularExpression) { return "http \(s[r])" }
        return "error"
    }
}
