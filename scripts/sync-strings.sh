#!/usr/bin/env bash
# Copy the iOS apps' words into the shared modules, where the Kotlin side
# reads them as they are (same keys, same placeholders):
# - Maurice: its Localizable.strings, file for file;
# - Carnet: its Localizable.xcstrings, written out as one <lang>.strings per
#   language (a key a language has no translation for is left out of that
#   language, so the English one applies, as on iOS).
# The iOS repos sit beside the main checkout, also when this runs in a worktree.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
main="$(cd "$(git -C "$here" rev-parse --git-common-dir)/.." && pwd)"

src="${MAURICE_IOS:-$(dirname "$main")/maurice-ios}/Maurice/Resources"
dst="$here/maurice/shared/src/commonMain/composeResources/files/strings"
mkdir -p "$dst"
for dir in "$src"/*.lproj; do
  lang="$(basename "$dir" .lproj)"
  cp "$dir/Localizable.strings" "$dst/$lang.strings"
done
ls "$dst"

xcstrings="${CARNET_IOS:-$(dirname "$main")/carnet}/CarnetApp/Localizable.xcstrings"
dst="$here/carnet/shared/src/commonMain/composeResources/files/strings"
mkdir -p "$dst"
python3 -I - "$xcstrings" "$dst" <<'PY'
import json, sys
src, dst = sys.argv[1], sys.argv[2]
catalog = json.load(open(src, encoding="utf-8"))
languages = ["en", "fr", "de", "es", "it", "nl", "pt"]

def escape(s):
    return (s.replace("\\", "\\\\").replace('"', '\\"')
             .replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r"))

for lang in languages:
    lines = ["/* Carnet's words, from CarnetApp/Localizable.xcstrings (scripts/sync-strings.sh). Do not edit. */"]
    for key in sorted(catalog["strings"]):
        unit = catalog["strings"][key].get("localizations", {}).get(lang, {}).get("stringUnit")
        if not unit or "value" not in unit:
            continue
        lines.append(f'"{escape(key)}" = "{escape(unit["value"])}";')
    with open(f"{dst}/{lang}.strings", "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
PY
ls "$dst"
