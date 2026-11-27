#!/bin/bash
# The parity suite: the iOS apps' Swift code and the Android apps' Kotlin code
# run the same cases against the same fake household, and Android must
# observe what iOS observes. See parity/README.md.
#
#   scripts/parity.sh                 # iOS goldens, then the Android check
#   scripts/parity.sh --ios           # only regenerate the iOS goldens
#   scripts/parity.sh --android       # only check Android against the goldens
#   scripts/parity.sh --app maurice   # one app (maurice | carnet)
#   scripts/parity.sh --only Class    # one Swift class / Kotlin test class
#
# Env: MAURICE_IOS (default ../maurice/app), CARNET_IOS (default ../carnet),
# PARITY_PORT (47800), PARITY_SIM (udid; default: the simulator named
# $PARITY_SIM_NAME, "Parity iPhone", created if missing — never one other
# sessions use: the tests wipe its keychain and defaults), PARITY_LANG (en).
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
# The iOS repos sit beside the main checkout, also when this runs in a worktree.
main_checkout="$(cd "$(git -C "$repo" rev-parse --git-common-dir)/.." && pwd)"
workspaces="$(dirname "$main_checkout")"
export MAURICE_IOS="${MAURICE_IOS:-$workspaces/maurice/app}"
export CARNET_IOS="${CARNET_IOS:-$workspaces/carnet}"
export PARITY_PORT="${PARITY_PORT:-47800}"
export PARITY_ROOT="$repo/parity"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home}"

do_ios=1; do_android=1; apps=(maurice carnet); only=""
while [ $# -gt 0 ]; do
  case "$1" in
    --ios) do_android=0 ;;
    --android) do_ios=0 ;;
    --app) shift; apps=("$1") ;;
    --only) shift; only="$1" ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

# The fake household, unless one is already up.
server_pid=""
if ! curl -sf "http://127.0.0.1:$PARITY_PORT/__parity/log" >/dev/null 2>&1; then
  mkdir -p "$repo/parity/build"
  bun "$repo/parity/server/fake-household.ts" >"$repo/parity/build/server.log" 2>&1 &
  server_pid=$!
  trap '[ -n "$server_pid" ] && kill $server_pid 2>/dev/null' EXIT
  for _ in $(seq 50); do
    curl -sf "http://127.0.0.1:$PARITY_PORT/__parity/log" >/dev/null 2>&1 && break
    sleep 0.1
  done
fi

status=0

if [ "$do_ios" = 1 ]; then
  export PARITY_MAURICE_COMMIT="$(git -C "$MAURICE_IOS" rev-parse --short HEAD)$(git -C "$MAURICE_IOS" diff --quiet HEAD -- . || echo '+dirty')"
  export PARITY_CARNET_COMMIT="$(git -C "$CARNET_IOS" rev-parse --short HEAD)$(git -C "$CARNET_IOS" diff --quiet HEAD -- . || echo '+dirty')"
  sim_name="${PARITY_SIM_NAME:-Parity iPhone}"
  sim="${PARITY_SIM:-$(xcrun simctl list devices -j | python3 -c '
import json,sys
for rt, devs in json.load(sys.stdin)["devices"].items():
    for d in devs:
        if d["name"] == sys.argv[1] and d["isAvailable"]: print(d["udid"]); sys.exit()
' "$sim_name")}"
  if [ -z "$sim" ]; then
    runtime="$(xcrun simctl list runtimes -j | python3 -c 'import json,sys; print([r["identifier"] for r in json.load(sys.stdin)["runtimes"] if r["platform"]=="iOS" and r["isAvailable"]][-1])')"
    sim="$(xcrun simctl create "$sim_name" "iPhone 17 Pro" "$runtime")"
  fi
  xcrun simctl boot "$sim" 2>/dev/null || true
  xcrun simctl bootstatus "$sim" -b >/dev/null

  cd "$repo/parity/ios"
  xcodegen -q
  for app in "${apps[@]}"; do
    scheme="$([ "$app" = carnet ] && echo CarnetParity || echo MauriceParity)"
    log="$repo/parity/build/ios-$app.log"
    echo "▸ iOS $app: building ($scheme, $( [ "$app" = carnet ] && echo "$PARITY_CARNET_COMMIT" || echo "$PARITY_MAURICE_COMMIT"))"
    if ! xcodebuild build-for-testing -project Parity.xcodeproj -scheme "$scheme" \
        -destination "platform=iOS Simulator,id=$sim" -derivedDataPath build/dd >"$log" 2>&1; then
      grep -E "error:" "$log" | sort -u | head -30
      echo "✗ iOS $app: build failed ($log)"; status=1; continue
    fi
    echo "▸ iOS $app: running"
    # One process per class: the apps' stores leave tasks and sockets behind
    # that can bring down a later class run in the same process.
    dir="$([ "$app" = carnet ] && echo Carnet || echo Maurice)"
    if [ -n "$only" ]; then classes=("$only"); else
      classes=(); for f in Tests/$dir/*Parity.swift; do classes+=("$(basename "$f" .swift)"); done
    fi
    ran=0; broke=()
    for cls in "${classes[@]}"; do
      if ./sim-xctest.sh "$sim" build/dd/Build/Products/Debug-iphonesimulator "$scheme" "$cls" >>"$log" 2>&1; then
        ran=$((ran + 1))
      else
        broke+=("$cls")
      fi
    done
    if [ ${#broke[@]} -eq 0 ]; then
      echo "Executed $ran class(es), with 0 failures"
    else
      grep -E "^✗|signal" "$log" | head -30
      echo "✗ iOS $app: ${broke[*]} failed or crashed ($log)"; status=1
    fi
  done
  cd "$repo"
fi

if [ "$do_android" = 1 ]; then
  tasks=(":core:jvmTest")
  for app in "${apps[@]}"; do tasks+=(":$app:shared:jvmTest"); done
  filter="eu.chezmaurice.parity.*"
  [ -n "$only" ] && filter="eu.chezmaurice.parity.$only"
  echo "▸ Android: ${tasks[*]}"
  for m in core maurice/shared carnet/shared; do rm -rf "$repo/$m/build/test-results/jvmTest"; done
  # One module at a time: they share the fake household, and Gradle may run
  # tasks side by side (org.gradle.parallel in ~/.gradle), each resetting
  # the household's script under the others' feet.
  : >"$repo/parity/build/android.log"
  for t in "${tasks[@]}"; do
    "$repo/gradlew" -p "$repo" "$t" --tests "$filter" --no-parallel -q >>"$repo/parity/build/android.log" 2>&1 || true
  done
  # Gradle says little of a failed test; its JUnit reports say what differs.
  python3 - "$repo" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
repo = sys.argv[1]
passed = failed = 0
for f in sorted(glob.glob(f"{repo}/*/build/test-results/jvmTest/TEST-eu.chezmaurice.parity.*.xml") +
                glob.glob(f"{repo}/*/shared/build/test-results/jvmTest/TEST-eu.chezmaurice.parity.*.xml")):
    for tc in ET.parse(f).getroot().iter("testcase"):
        fails = list(tc.iter("failure")) + list(tc.iter("error"))
        if fails:
            failed += 1
            print(f"✗ {tc.get('classname').split('.')[-1]}")
            print("  " + (fails[0].get("message") or fails[0].text or "").strip().replace("\n", "\n  ")[:3000])
        else:
            passed += 1
print(f"Android: {passed} domain test(s) agree, {failed} differ")
# The differences classified and kept (`"accepted": {"kind": …}` in the vectors).
import collections
kinds = collections.Counter()
for f in glob.glob(f"{repo}/parity/build/android/*/*.accepted.tsv"):
    for line in open(f, encoding="utf-8"):
        if line.strip():
            kinds[line.split("\t", 1)[0]] += 1
if kinds:
    print("Accepted, by kind: " + ", ".join(f"{k} {n}" for k, n in sorted(kinds.items())) + "  (parity/build/android/*/*.accepted.tsv)")
PY
  if grep -qE "error:|Compilation error|BUILD FAILED" "$repo/parity/build/android.log" && ! grep -q "There were failing tests" "$repo/parity/build/android.log"; then
    grep -E "^e: |error:" "$repo/parity/build/android.log" | head -30
    echo "✗ Android: build failed (parity/build/android.log)"; status=1
  elif grep -q "There were failing tests" "$repo/parity/build/android.log"; then
    echo "✗ Android differs from iOS — every difference in full: parity/build/android/<app>/<domain>.diff.txt"; status=1
  else
    echo "✓ Android agrees with iOS on every case"
  fi
fi

# The routes each app calls, read from the source (views included): what the
# behavioural cases cannot reach still shows here. Informational.
echo "▸ Routes iOS calls and Android never does (parity/endpoints.py):"
for app in "${apps[@]}"; do
  python3 "$repo/parity/endpoints.py" --app "$app" | awk '/^──/{print} /called by iOS/{on=1; print; next} /query parameters|Android only|full scope/{on=0} on' || true
done

exit $status
