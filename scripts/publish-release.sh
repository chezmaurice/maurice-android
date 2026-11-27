#!/usr/bin/env bash
# Build the signed APK and publish it as a release of the app's public
# releases-only repository, where Obtainium finds it:
#   Maurice → chezmaurice/maurice-android-releases
#   Carnet  → chezmaurice/carnet-android-releases
#
#   scripts/publish-release.sh "What changed, in a line or two"          # Maurice
#   scripts/publish-release.sh carnet "What changed, in a line or two"   # Carnet
#
# The version is the app's versionName; bump versionCode and versionName in
# <app>/androidApp/build.gradle.kts first. Only APKs go there, never code.
# One repository per app: Obtainium takes a repository's latest release, and
# would take one app's APK for the other's update.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
# Obtainium serves GrapheneOS phones: only the `foss` fork, which has no Google
# dependency, ever goes there. `main` is the Play Store build.
branch="$(git -C "$here" rev-parse --abbrev-ref HEAD)"
[[ "$branch" == "foss" ]] || { echo "publish from the foss branch (here: $branch): main is the Play Store build" >&2; exit 1; }
app=maurice
if [[ "${1:-}" == maurice || "${1:-}" == carnet ]]; then app="$1"; shift; fi
notes="${1:?release notes required}"
title="$(tr '[:lower:]' '[:upper:]' <<< "${app:0:1}")${app:1}"
apk="$("$here/scripts/build-release.sh" "$app" | tail -1)"
version="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$here/$app/androidApp/build.gradle.kts")"
gh release create "v$version" "$here/$apk" -R "chezmaurice/$app-android-releases" --title "$title $version" --notes "$notes"
