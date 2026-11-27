#!/usr/bin/env bash
# Build signed releases into dist/.
#
#   scripts/build-release.sh            # Maurice
#   scripts/build-release.sh carnet     # Carnet
#
# What it builds follows the branch: on `main` (the Play Store build, which
# carries Google Play Billing), Maurice is the bundle for the Play Store (.aab);
# on `foss` (the Obtainium/GrapheneOS fork, no Google at all), both apps are
# the APKs of the releases repos. Carnet is an APK either way.
#
# The key: ~/.maurice-android/release.jks (outside the repo). Its password:
# the macOS Keychain item "maurice-android-release". Every version must be
# signed with this same key, or installed copies cannot be updated.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
app="${1:-maurice}"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home}"
export MAURICE_ANDROID_KEYSTORE="$HOME/.maurice-android/release.jks"
[[ -f "$MAURICE_ANDROID_KEYSTORE" ]] || { echo "no key at $MAURICE_ANDROID_KEYSTORE" >&2; exit 1; }
MAURICE_ANDROID_KEYSTORE_PASSWORD="$(security find-generic-password -s maurice-android-release -w)"
export MAURICE_ANDROID_KEYSTORE_PASSWORD

cd "$here"
version="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$app/androidApp/build.gradle.kts")"
mkdir -p dist

# The Play build: Maurice from anywhere but the foss branch.
if [[ "$app" == "maurice" && "$(git -C "$here" rev-parse --abbrev-ref HEAD)" != "foss" ]]; then
  ./gradlew --console=plain -q ":$app:androidApp:bundleRelease"
  out="dist/$app-$version.aab"
  cp "$app/androidApp/build/outputs/bundle/release/androidApp-release.aab" "$out"
  keytool -printcert -jarfile "$out" 2>/dev/null | grep -E "SHA256" || true
  echo "$out"
  exit 0
fi

./gradlew --console=plain -q ":$app:androidApp:assembleRelease"
out="dist/$app-$version.apk"
cp "$app/androidApp/build/outputs/apk/release/androidApp-release.apk" "$out"
"$HOME/Library/Android/sdk/build-tools/37.0.0/apksigner" verify --print-certs "$out" | grep -E "SHA-256" || true
echo "$out"
