import XCTest
import Security
final class SpikeTests: XCTestCase {
  func testKeychain() {
    let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "parity.spike", kSecAttrAccount as String: "k", kSecValueData as String: Data("v".utf8)]
    SecItemDelete(q as CFDictionary)
    let st = SecItemAdd(q as CFDictionary, nil)
    print("PARITY keychain add status", st)
  }
}
