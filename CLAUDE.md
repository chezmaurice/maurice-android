# maurice-android

Android and desktop (Windows, Linux) versions of Maurice and Carnet, in Kotlin
Multiplatform + Compose Multiplatform. Public (AGPL-3.0, `LICENSE`, `NOTICE.md`):
`chezmaurice/maurice-android` starts from one snapshot of 27 November 2026; the
history before it is in private archives, which no branch of this repository
must ever be pushed from or to. Nothing private in the tree: no real member,
household, address or key, and nothing of the tools taken out of the product
(health, signals, coaching, tasks, expenses, places, research, dossiers,
dashboards). `scripts/check-personal-data.py` runs before every push
(`.githooks/pre-push`, installed with `--install`) and in CI; the private list
of names it reads stays outside the repository. The iOS/macOS apps stay
in SwiftUI (`../maurice/app`, `../carnet`); the server is `../maurice/server`.

**Desktop means Windows and Linux only.** The Mac has its SwiftUI app: no
macOS build, package, signing or Cmd shortcut here. Running the desktop app on
the Mac is only a development convenience.

## Layout

- `core/` — everything the apps say to the household server: API client,
  pairing, session, NDJSON chat stream, dictation upload. No UI.
- `ui/` — the look both apps share.
- `maurice/{shared,androidApp,desktopApp}`, `carnet/{shared,androidApp,desktopApp}`
  — screens live in `shared` (common code); `androidApp` and `desktopApp` are
  thin launchers.
- `specs/` — the server contract as the iOS apps use it. When the Swift code
  and a spec disagree, the Swift code wins; fix the spec.

## Rules

- **Two lines since 4 November 2026.** `main` is the Play Store build: it may
  carry Google dependencies (Play Billing for the subscription and top-ups,
  later Firebase push). The `foss` branch is the Obtainium/GrapheneOS fork,
  with no Google dependency at all (no Firebase, Play Services, ML Kit, Play
  Integrity; push through UnifiedPush). Everything that is not about
  purchases or Google services is merged into `foss` too; keep Google-only
  code in files of its own (`maurice/androidApp/.../Billing.kt`) so the
  merges stay clean. Obtainium releases (`scripts/publish-release.sh`) come
  from `foss` only.
- **Multiplatform libraries only in `commonMain`** (Ktor, kotlinx.serialization,
  coroutines, Compose Multiplatform). Platform code behind `expect`/`actual`.
- **Parity with iOS** is tracked in `parity.md`; `scripts/ios-delta.sh` lists
  the iOS commits not carried over yet. Port the behaviour, not the Swift
  structure.
- The release keystore never enters the repo (see `.gitignore`).

## Build

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
./gradlew :maurice:desktopApp:run                 # desktop window (dev only on the Mac)
./gradlew :maurice:androidApp:assembleDebug       # APK
scripts/build-release.sh                          # on main: the Play Store bundle (.aab); on foss: the APK
./gradlew :core:jvmTest                           # core tests
~/Library/Android/sdk/emulator/emulator -avd Maurice_AOSP_36   # Google-free emulator
```
