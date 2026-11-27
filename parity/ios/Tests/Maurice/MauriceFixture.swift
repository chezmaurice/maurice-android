import Foundation
import Security

/// A device paired with the fake household, one member signed in — the
/// iOS side of `MauriceFixture.kt`. Every call starts from a blank device:
/// UserDefaults and the token vault wiped.
@MainActor
enum MauriceFixture {
    static let householdName = "Parity"

    /// A fresh SessionStore on the fake household, `member` signed in with
    /// `token`. `role` and `displayName` fill the member as the picker keeps it.
    static func session(
        member: String = "u1", token: String = "tok-u1",
        displayName: String = "Alice", role: String? = "standard"
    ) -> SessionStore {
        wipe()
        let s = SessionStore()
        s.pair(serverURL: Parity.baseURL, name: householdName)
        s.updateDeviceUsers([DeviceUser(id: member, displayName: displayName, avatarColor: "#6b6460",
                                        hasPin: false, role: role, avatarURL: nil, username: nil)])
        s.loginUser(member, token: token)
        return s
    }

    /// A blank device: no household, no token.
    static func wipe() {
        if let id = Bundle.main.bundleIdentifier { UserDefaults.standard.removePersistentDomain(forName: id) }
        for key in UserDefaults.standard.dictionaryRepresentation().keys where key.hasPrefix("maurice.") {
            UserDefaults.standard.removeObject(forKey: key)
        }
        SecItemDelete([kSecClass as String: kSecClassGenericPassword] as CFDictionary)
    }
}
