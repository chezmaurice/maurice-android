#!/bin/bash
# Build and install the apps the UI layer drives.
#
#   parity/ui/install.sh ios      # the iOS apps (../maurice/app, ../carnet as they are) on the parity simulator
#   parity/ui/install.sh android  # this repo's debug APKs on the emulator (adb's first device, or $ANDROID_SERIAL)
#
# Env: PARITY_SIM_NAME (default "Parity iPhone"), MAURICE_IOS, CARNET_IOS.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
main_checkout="$(cd "$(git -C "$repo" rev-parse --git-common-dir)/.." && pwd)"
workspaces="$(dirname "$main_checkout")"
MAURICE_IOS="${MAURICE_IOS:-$workspaces/maurice/app}"
CARNET_IOS="${CARNET_IOS:-$workspaces/carnet}"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home}"
adb="$HOME/Library/Android/sdk/platform-tools/adb"

case "${1:?ios or android}" in
  ios)
    name="${PARITY_SIM_NAME:-Parity iPhone}"
    udid="$(xcrun simctl list devices -j | python3 -c '
import json,sys
for devs in json.load(sys.stdin)["devices"].values():
    for d in devs:
        if d["name"] == sys.argv[1] and d["isAvailable"]: print(d["udid"]); sys.exit()
' "$name")"
    [ -n "$udid" ] || { echo "no simulator named $name (scripts/parity.sh --ios creates it)" >&2; exit 1; }
    xcrun simctl boot "$udid" 2>/dev/null || true
    dd="$here/build/dd"
    xcodebuild -project "$MAURICE_IOS/Maurice.xcodeproj" -scheme Maurice_iOS -destination "id=$udid" -derivedDataPath "$dd" build >"$here/build/ios-maurice.log" 2>&1 \
      || { grep error: "$here/build/ios-maurice.log" | head; exit 1; }
    xcodebuild -project "$CARNET_IOS/Carnet.xcodeproj" -scheme Carnet -destination "id=$udid" -derivedDataPath "$dd" build >"$here/build/ios-carnet.log" 2>&1 \
      || { grep error: "$here/build/ios-carnet.log" | head; exit 1; }
    xcrun simctl install "$udid" "$dd/Build/Products/Debug-iphonesimulator/Maurice.app"
    xcrun simctl install "$udid" "$dd/Build/Products/Debug-iphonesimulator/Carnet.app"
    echo "installed on $name: maurice $(git -C "$MAURICE_IOS" rev-parse --short HEAD), carnet $(git -C "$CARNET_IOS" rev-parse --short HEAD)"
    ;;
  android)
    "$repo/gradlew" -p "$repo" -q :maurice:androidApp:assembleDebug :carnet:androidApp:assembleDebug
    "$adb" install -r "$repo/maurice/androidApp/build/outputs/apk/debug/androidApp-debug.apk" | tail -1
    "$adb" install -r "$repo/carnet/androidApp/build/outputs/apk/debug/androidApp-debug.apk" | tail -1
    echo "installed: $(git -C "$repo" rev-parse --short HEAD)"
    ;;
esac
