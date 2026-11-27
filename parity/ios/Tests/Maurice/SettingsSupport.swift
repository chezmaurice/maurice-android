import Foundation

/// Helpers of the Settings lot (SettingsReportsParity, SettingsMailParity,
/// SettingsDataParity, SettingsDictationParity, SettingsStoreParity).
enum SettingsSupport {
    /// The household's log, only the requests whose path starts with one of `prefixes`.
    static func requests(_ prefixes: String...) async -> [[String: Any]] {
        (await Parity.requests() as? [[String: Any]] ?? []).filter { r in
            let path = r["path"] as? String ?? ""
            return prefixes.contains { path.hasPrefix($0) }
        }
    }

    /// A JSON value decoded as `T` by a plain JSONDecoder (APIClient's), or nil.
    static func decode<T: Decodable>(_ type: T.Type, _ c: ParityCase, _ key: String = "json") -> T? {
        try? JSONDecoder().decode(T.self, from: c.data(key))
    }
}
