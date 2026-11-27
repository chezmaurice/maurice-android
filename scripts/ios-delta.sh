#!/usr/bin/env bash
# List the iOS commits not yet carried over to Android: everything after the
# pointer recorded in parity.md that touches the app's own code.
#
#   scripts/ios-delta.sh            # both apps
#   scripts/ios-delta.sh maurice    # one of them
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
# The iOS repos sit beside the main checkout, also when this runs from a
# worktree (.claude/worktrees/<name>): find the main checkout through git.
main="$(dirname "$(git -C "$here" rev-parse --path-format=absolute --git-common-dir)")"
workspaces="$(dirname "$main")"

pointer() { # app name → commit recorded in parity.md
  awk -F'|' -v app="$1" 'tolower($2) ~ app { gsub(/[ `]/, "", $4); print $4 }' "$here/parity.md"
}

delta() { # name, repo dir, paths…
  local name="$1" repo="$2"; shift 2
  local from; from="$(pointer "$name")"
  git -C "$repo" fetch -q origin main 2>/dev/null || true
  local head=main
  git -C "$repo" rev-parse -q --verify origin/main >/dev/null && head=origin/main
  local n; n="$(git -C "$repo" rev-list --count "$from..$head" -- "$@")"
  echo "== $name: $n commit(s) since $from"
  git -C "$repo" log --reverse --format='%h %ad %s' --date=short "$from..$head" -- "$@"
}

which="${1:-all}"
if [[ "$which" == all || "$which" == maurice ]]; then
  delta maurice "$workspaces/maurice" app/Maurice app/Shared app/MauriceShareExtension
fi
if [[ "$which" == all || "$which" == carnet ]]; then
  delta carnet "$workspaces/carnet" CarnetApp CarnetShareExtension Shared
fi
