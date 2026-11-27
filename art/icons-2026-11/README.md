# Maurice and Carnet icons, November 2026 redesign

The new icons were made with Claude Design and approved by Candide on 2 November 2026. The emblems stay the same: the bowler hat for Maurice and the pipe for Carnet. The background is now a grid of pastel blocks: light (#F5F0E8) for Maurice, dark (#1F2221) for Carnet. This folder holds only the non-Apple sets. The Apple sets go into the Apple apps.

These files are not wired in yet. Here is what is left to do for each app (`maurice/` and `carnet/`):

- **Android**: `android/res/` replaces the current launcher resources in `<app>/androidApp/src/main/res/`.
  - Watch out: today, `mipmap-anydpi-v26/ic_launcher.xml` uses a solid colour as its background (`@color/ic_launcher_background`, #F2ECE2 for Maurice). The new icon has a background image instead, `@mipmap/ic_launcher_background`, because that is where the blocks are. Use the export's `ic_launcher.xml` and `ic_launcher_round.xml`, then remove `values/ic_launcher_background.xml` if nothing else uses it.
  - The export also includes the legacy `ic_launcher.png` and `ic_launcher_round.png` icons, for API < 26.
  - `play-store-512.png` is the Play Console icon.
- **Desktop (Compose)**: `windows/<app>.ico` replaces `desktopApp/icons/<app>.ico`. `linux/hicolor/512x512/apps/<app>.png` replaces `desktopApp/icons/<app>.png`. Also check `desktopApp/src/main/resources/icon.png` (512 px, the window icon).
- **Web/PWA**: `web/` holds favicon.ico, the 192/512 icons and the maskable icon, if a target uses them.
- **Sources**: `layers/` contains the background and blocks as SVG, and the emblem as PNG.

Sizes of 32 px and below have no blocks and show a larger emblem, so they stay readable.
Once this is wired in, `art/<app>-icon-1024.png` can be replaced too. The 1024 icon is `play-store-512.png` scaled up, or the Apple export.
