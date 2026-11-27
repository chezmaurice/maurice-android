#!/bin/bash
# The UI layer of the parity suite (parity/ui/drive.py): the fake household
# up, then the scenarios on one platform.
#
#   parity/ui/run.sh ios     [scenario.json…]   # default: every scenario
#   parity/ui/run.sh android [scenario.json…]
#
# The apps must be installed first: parity/ui/install.sh ios | android.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
export PARITY_PORT="${PARITY_PORT:-47800}"
platform="${1:?ios or android}"; shift
if [ $# -eq 0 ]; then set -- "$here"/scenarios/*.json; fi

if ! curl -sf "http://127.0.0.1:$PARITY_PORT/__parity/log" >/dev/null 2>&1; then
  mkdir -p "$repo/parity/build"
  bun "$repo/parity/server/fake-household.ts" >"$repo/parity/build/server-ui.log" 2>&1 &
  server=$!
  trap 'kill $server 2>/dev/null' EXIT
  for _ in $(seq 50); do curl -sf "http://127.0.0.1:$PARITY_PORT/__parity/log" >/dev/null 2>&1 && break; sleep 0.1; done
fi
python3 "$here/drive.py" "$platform" "$@"
