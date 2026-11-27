#!/bin/bash
# Runs a parity test bundle inside a simulator through its runner (see
# Runner/main.swift for why neither `xcodebuild test` nor the stock xctest
# agent will do).
#
#   sim-xctest.sh <simulator udid> <products dir> <Bundle> [Class[/test]]
set -euo pipefail
udid="$1"; products="$(cd "$2" && pwd)"; bundle="$3"; only="${4:-}"
runner="${bundle%Parity}Runner"
platform="$(xcrun --sdk iphonesimulator --show-sdk-platform-path)"
export SIMCTL_CHILD_DYLD_FRAMEWORK_PATH="$platform/Developer/Library/Frameworks"
export SIMCTL_CHILD_DYLD_LIBRARY_PATH="$platform/Developer/usr/lib"
# Environment the tests read (PARITY_*) is passed through to the child.
while IFS='=' read -r name value; do
  case "$name" in PARITY_*) export "SIMCTL_CHILD_$name=$value" ;; esac
done < <(env)
# One time zone for both halves (Parity.kt sets the JVM's): dates shown to the member.
export SIMCTL_CHILD_TZ="${PARITY_TZ:-Europe/Paris}"
args=("$products/$bundle.xctest")
[ -n "$only" ] && args+=("$only")
# The language both halves compare in (Parity.kt sets the JVM's to match).
lang="${PARITY_LANG:-en}"
# The region that goes with it (en_US, fr_FR…): number and date formats.
case "$lang" in en) region=US ;; *) region="$(echo "$lang" | tr a-z A-Z)" ;; esac
args+=(-AppleLanguages "($lang)" -AppleLocale "${lang}_$region")
exec xcrun simctl spawn "$udid" "$products/$runner.app/$runner" "${args[@]}"
