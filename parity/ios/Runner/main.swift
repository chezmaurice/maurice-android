import Foundation
import XCTest

// The process the parity tests run in, on the simulator. Not `xcodebuild
// test`: its runner hangs on this Mac's simulators before connecting. Not the
// platform's xctest agent either: the simulator's keychain refuses it (-34018,
// no access group), and SessionStore keeps its tokens there. This app carries
// the entitlements the agent lacks, loads the test bundle and runs it.
//
//   ParityRunner <path/to/Bundle.xctest> [Class[/testMethod]]

final class Reporter: NSObject, XCTestObservation {
    var failures = 0
    func testCase(_ testCase: XCTestCase, didRecord issue: XCTIssue) {
        failures += 1
        let at = issue.sourceCodeContext.location.map { "\($0.fileURL.lastPathComponent):\($0.lineNumber)" } ?? ""
        print("✗ \(testCase.name) \(at): \(issue.compactDescription)")
    }
    func testCaseDidFinish(_ testCase: XCTestCase) {
        let run = testCase.testRun!
        print("\(run.hasSucceeded ? "✓" : "✗") \(testCase.name) (\(String(format: "%.2f", run.totalDuration)) s)")
    }
}

// Positional arguments only: what follows is NSUserDefaults' (-AppleLanguages).
let args = Array(CommandLine.arguments.prefix { !$0.hasPrefix("-") })
guard args.count > 1, let bundle = Bundle(path: args[1]), bundle.load() else {
    print("ParityRunner: cannot load \(args.dropFirst().first ?? "<no bundle>")")
    exit(2)
}
let reporter = Reporter()
XCTestObservationCenter.shared.addTestObserver(reporter)

var suite = XCTestSuite.default
if args.count > 2 {
    let parts = args[2].split(separator: "/").map(String.init)
    let module = bundle.bundleURL.deletingPathExtension().lastPathComponent
    guard let cls = NSClassFromString("\(module).\(parts[0])") as? XCTestCase.Type else {
        print("ParityRunner: no test class \(parts[0]) in \(module)")
        exit(2)
    }
    if parts.count > 1 {
        suite = XCTestSuite(name: args[2])
        suite.addTest(cls.init(selector: NSSelectorFromString(parts[1])))
    } else {
        suite = XCTestSuite(forTestCaseClass: cls)
    }
}
suite.run()
let run = suite.testRun!
print("Executed \(run.executionCount) tests, with \(run.totalFailureCount) failures")
exit(run.totalFailureCount == 0 ? 0 : 1)
