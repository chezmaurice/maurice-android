# Parity suite — Android against iOS

The iOS apps are the reference (CLAUDE.md: "the Swift code wins"). This suite
runs the **same cases** through the iOS apps' own Swift code and through the
Android apps' Kotlin code, against **the same fake household**, and fails
wherever Android observes something iOS does not.

```sh
scripts/parity.sh                     # both apps: iOS goldens, then the Android check
scripts/parity.sh --app carnet        # one app
scripts/parity.sh --only ChatParity   # one Swift class / Kotlin class (same name on both sides)
scripts/parity.sh --android           # only Android, against the goldens in the repo (no Xcode needed)
scripts/parity.sh --ios               # only regenerate the iOS goldens
```

## How it works

```
parity/
  vectors/<app>/<domain>.json    the cases: inputs, and the household's answers (routes)
  golden/<app>/<domain>.json     what the iOS code observed on each case (written by the Swift half, committed)
  build/android/<app>/…          what the Android code observed (for a diff by eye; not committed)
  server/fake-household.ts       the fake household (Bun), port 47800
  ios/                           the Swift half: test bundles compiled from ../maurice/app and ../carnet as they are
  android/harness/               the Kotlin half's harness, compiled into each module's jvmTest
  android/{core,maurice,carnet}/ the Kotlin tests: core, maurice/shared, carnet/shared (internal APIs reachable)
```

- **Swift half.** `parity/ios/project.yml` compiles the iOS apps' sources, read
  in place and never modified, into two unit-test bundles (`MauriceParity`,
  `CarnetParity`). Every type, `internal` included, is in the test's module; only
  `private`/`fileprivate` is out of reach. They run on a simulator of their own
  ("Parity iPhone", wiped freely) through `MauriceRunner`/`CarnetRunner`, an app
  that carries a keychain access group (the token vault) and the app's
  languages (`L()` reads `Bundle.main`). `xcodebuild test` is not used: its
  runner hangs on this Mac's simulators.
- **Kotlin half.** `Parity.check(app, domain) { case -> observation }` runs the
  cases and diffs against the golden, case by case. Numbers compare by value,
  objects by key (order is not behaviour).
- **The fake household.** A case's `routes` are loaded before it runs; every
  request is logged (method, path, query, `Authorization`, content type, body
  parsed). `Parity.requests()` returns the log; `Parity.exchange { … }` returns
  `{requests, result}`. Unmatched requests get a 404 and are logged
  `unmatched: true`.
- **Language.** Both halves run in `PARITY_LANG` (default `en`): the
  simulator process gets `-AppleLanguages (en)`, the JVM `Locale.setDefault`.

## Writing a domain

1. `parity/vectors/<app>/<domain>.json`:
   ```json
   { "domain": "pin-rules", "about": "what behaviour this pins down",
     "ios": "file — function", "android": "file — function",
     "cases": [ { "name": "unique, readable", "input": …, "routes": [ … ] } ] }
   ```
   Aim for the edges: empty, missing fields, nulls, unknown values, Unicode,
   errors (4xx/5xx, malformed JSON), the order things arrive in.
2. Swift: `parity/ios/Tests/<Maurice|Carnet>/<Name>Parity.swift`, class
   `<Name>Parity: XCTestCase`, one test calling `Parity.run(app, domain) { c in … }`.
3. Kotlin: `parity/android/<core|maurice|carnet>/eu/chezmaurice/parity/<Name>Parity.kt`,
   class `<Name>Parity`, one `@Test` calling `Parity.check(app, domain) { c -> … }`.
   Use the module where the Kotlin code lives (core for `core/`).
4. `scripts/parity.sh --only <Name>Parity`, read the differences, decide.

**What to observe.** What the member would see or the server would receive —
not each side's internal structure. Build the observation from the *result* of
the code (state after the call, the value returned, the requests sent), shaped
identically on both sides. Never re-implement the logic in the adapter: an
adapter only reads fields and renames them. If an iOS behaviour sits in a
`private` function or a SwiftUI `View`'s body, drive the nearest reachable
caller instead (a store method, a static helper), and list what stays out of
reach in the coverage table below.

**Fixtures.** `MauriceFixture.session()` (Swift and Kotlin): a blank device
paired with the fake household, member `u1` signed in with token `tok-u1`.

**A difference.** Each failing case is a finding: Android does not do what
iOS does. Fix Android (in its own commit), or classify it — mark the case
`"accepted": {"kind": "<kind>", "why": "<why>"}` with one of:

| kind | when |
|---|---|
| `lenient` | Android reads leniently a malformed answer iOS rejects. Kept on purpose (11 November 2026) — except where Android is the stricter one: that is an Android bug. |
| `wire` | A request detail neither the member nor the server sees a difference in (`{}` or no body, `null` or no field). |
| `ios-bug` | iOS is wrong and Android right. The mark carries `"issue"`: the GitHub issue of the iOS repository or, for a security matter, the private advisory it is filed as; `parity/ios-bugs.py` lists them. |
| `platform` | A deliberate platform difference, recorded in `parity.md`. |
| `not-ported` | An iOS feature Android does not have yet (`scripts/ios-delta.sh`). |
| `latent` | A difference no real input reaches today. |

`scripts/parity.sh` fails only on what is not classified, and counts the
rest by kind. A mark whose two sides agree again fails too: drop it.
**The release gate: no unclassified difference.**

**When iOS moves.** Re-run `scripts/parity.sh`: the goldens are rewritten from
the iOS code at its current commit (stamped in each golden's `source`); `git
diff parity/golden` shows what iOS now does differently.

## The UI layer

What the cases cannot reach — a flow in a SwiftUI view's body, a Compose
screen — is compared by driving the real apps: `parity/ui/`.

```sh
parity/ui/install.sh ios        # build ../maurice/app and ../carnet as they are, install on the parity simulator
parity/ui/install.sh android    # this repo's debug APKs on the emulator (Maurice_AOSP_36: no Google)
parity/ui/run.sh ios            # every scenario on iOS: writes parity/ui/golden/<name>.json
parity/ui/run.sh android        # the same on Android, compared
```

A scenario (`parity/ui/scenarios/<name>.json`) is a household (`routes`, and
the shared ones it names in `households` from `scenarios/common/`) and what a
member does: `see`, `tap` (by the text or accessibility label on screen; `nth`
or `last` among several, `hold` for a long press, `optional` for a system
prompt that may not come, `expect` to tap again until what it opens shows),
`tapxy` (a control with no label), `type` (`{base}` is the household's
address: 127.0.0.1 from the simulator, 10.0.2.2 from the emulator; `{host}`
its host), `clear`, `swipe` (a row slid `to` left or right), `scrollto`,
`scroll`, `back`, `gone`, `wait`, `include` (the steps of
`scenarios/common/<name>.json`, e.g. `carnet-sign-in`), `forget`, and
`checkpoint`; `only` or `skip` keep a step to one platform (the whole list in
`drive.py`'s docstring). A text may be `{"ios": …, "android": …}` where the two
apps word the same control differently — itself a finding. At each checkpoint
the requests the household received since the previous one must be the same
on both platforms, as a multiset (reads fired side by side come in no fixed
order), narrowed with `paths` when the screen reached loads a dozen things of
its own; `ignore` leaves requests out (an image one app takes from its cache);
the texts on one side only are listed for the eye. The app is stopped after
each scenario, and iOS's notification prompt is declined as it shows. Typed
text stays ASCII: AXe types nothing else. iOS is driven
with AXe (bundled with XcodeBuildMCP; `AXE=` to point elsewhere), Android with
adb and uiautomator. A scenario may carry `"accepted": {"kind", "why"}` like a
case (Android may then stop short), and so may a single checkpoint. Several sessions at once: each its own `PARITY_PORT`, `PARITY_SIM_NAME`
and `ANDROID_SERIAL`.

## Coverage

What is compared, and what is not yet — see `coverage.md`.
