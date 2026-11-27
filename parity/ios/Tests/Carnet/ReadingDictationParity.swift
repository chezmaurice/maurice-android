import XCTest

@MainActor
final class ReadingDictationParity: XCTestCase {
    func testReadingDictation() async {
        await Parity.run("carnet", "reading-dictation") { c in
            ReadingFixture.session()
            // As DictationMic.start builds it from the signed-in member.
            let household = HouseholdTranscription(baseURL: CarnetAPI.baseURL, token: CarnetAPI.authToken ?? "", preferred: true)
            let file = FileManager.default.temporaryDirectory.appendingPathComponent("parity-reading-\(UUID().uuidString).m4a")
            try Data((c.string("audio") ?? "").utf8).write(to: file)
            defer { try? FileManager.default.removeItem(at: file) }
            let seconds = (c["seconds"] as? NSNumber)?.doubleValue ?? 0
            let text = await household.transcribe(file, seconds: seconds)
            // Trimmed as Dictation.deliver hands it to the field (Android trims in transcribe).
            return ["requests": await Parity.requests(), "result": text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "failed"]
        }
    }
}
