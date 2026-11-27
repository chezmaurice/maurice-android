# Maurice and Carnet for Android, Windows and Linux

[Chez Maurice](https://www.chezmaurice.eu) is a household assistant that
lives on the household's own server (at home, or hosted): conversations with
Maurice, a garden of notes, a library, shared rooms. This repository holds
its Android and desktop apps, in Kotlin Multiplatform and Compose
Multiplatform:

- **Maurice**, the conversation app;
- **Carnet**, the reading and capture companion.

The server lives in [maurice-server](https://github.com/chezmaurice/maurice-server),
the iOS/macOS apps in [maurice-ios](https://github.com/chezmaurice/maurice-ios)
and [carnet](https://github.com/chezmaurice/carnet). These apps are ports of the
iOS ones; `parity.md` records how far the port has come.

## Installing

- **Obtainium** (Android, no Google services needed): add
  `https://github.com/chezmaurice/maurice-android-releases` and
  `https://github.com/chezmaurice/carnet-android-releases`.
- **Google Play**: internal testing for now.

## Two lines

- `main` is the Play Store build: it may use Google Play Billing.
- `foss` has no Google dependency at all (push through UnifiedPush); the
  Obtainium releases are built from it.

## Building

```sh
export JAVA_HOME=/path/to/jdk-17
./gradlew :maurice:desktopApp:run                 # desktop window
./gradlew :maurice:androidApp:assembleDebug       # APK
./gradlew :core:jvmTest                           # tests
```

Signed releases (`scripts/build-release.sh`) read the signing key and its
password from the environment; neither is in this repository.

## Layout

- `core/` — everything the apps say to the household server, no UI.
- `ui/` — the look both apps share.
- `maurice/`, `carnet/` — each app: `shared` (screens), `androidApp`,
  `desktopApp`.
- `specs/` — the server contract as the iOS apps use it.
- `parity/` — the suite that compares Android's behaviour with iOS's.

## Licence

GNU Affero General Public License v3 — see `LICENSE`. Third-party components:
`NOTICE.md`.
