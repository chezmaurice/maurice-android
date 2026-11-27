# Third-party components

Maurice and Carnet for Android, Windows and Linux are under the GNU AGPL v3
(see `LICENSE`). They are built on the components below, each under its own
licence; the versions are in `gradle/libs.versions.toml`.

## Libraries

| Component | Licence |
|---|---|
| Kotlin, kotlinx.coroutines, kotlinx.serialization, kotlinx-datetime (JetBrains) | Apache 2.0 |
| Compose Multiplatform: runtime, ui, foundation, material3, material-icons-core, components-resources, lifecycle, navigation (JetBrains, Google) | Apache 2.0 |
| AndroidX: activity-compose, CameraX (camera2, lifecycle, view), test runner and junit (Google) | Apache 2.0 |
| Ktor client: core, OkHttp engine, content negotiation, kotlinx.serialization JSON, mock (JetBrains) | Apache 2.0 |
| OkHttp (Square), brought by the Ktor engine | Apache 2.0 |
| Coil 3: coil-compose, coil-network-ktor3 | Apache 2.0 |
| multiplatform-markdown-renderer (Mike Penz) | Apache 2.0 |
| ZXing core (QR codes) | Apache 2.0 |
| UnifiedPush Android connector | Apache 2.0 |
| Tink (Google), brought by the UnifiedPush connector, with gson, protobuf-java, jsr305, error-prone annotations | Apache 2.0 (protobuf-java: BSD 3-Clause) |
| Google Play Billing Library — **`main` branch only**, the Play Store build; the `foss` branch has none | Android Software Development Kit License |

## Fonts and icons

| Component | Licence |
|---|---|
| Young Serif (The Young Serif Project Authors, Noir Blanc Rouge), `ui/src/commonMain/composeResources/font/` | SIL Open Font License 1.1, full text in `ui/OFL-YoungSerif.txt` |
| Material Symbols / Material Icons outlines used as glyphs (`ui/.../DomainGlyphs.kt`, `MauriceIcons.kt`, made by `scripts/domain-glyphs.py`) | Apache 2.0 |

The Maurice and Carnet icons and emblems (`art/`, the `mipmap-*` folders,
`emblems/`, `tiles/`) are part of this work.
