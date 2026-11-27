#!/usr/bin/env python3
"""Every server route each app calls, iOS against Android.

Reads the source of both, views included — what the behavioural suite cannot
reach (a request built inside a SwiftUI view body) still shows here. Paths are
normalised: interpolations become {}, the query string is dropped (its
parameter names are listed apart).

    python3 parity/endpoints.py [--app maurice|carnet] [--json]

Exit status 1 when iOS calls a route Android never does.
"""
import json
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.dirname(subprocess.run(["git", "-C", REPO, "rev-parse", "--path-format=absolute", "--git-common-dir"],
                                      capture_output=True, text=True).stdout.strip())
WORKSPACES = os.path.dirname(MAIN)
MAURICE_IOS = os.environ.get("MAURICE_IOS", f"{WORKSPACES}/maurice/app")
CARNET_IOS = os.environ.get("CARNET_IOS", f"{WORKSPACES}/carnet")

APPS = {
    # Carnet's Android screens borrow Maurice's (pairing, the member picker,
    # the conversation): core and maurice/shared count for both.
    "maurice": {
        "ios": [f"{MAURICE_IOS}/Maurice", f"{MAURICE_IOS}/Shared"],
        "android": [f"{REPO}/core/src/commonMain", f"{REPO}/maurice/shared/src", f"{REPO}/maurice/androidApp/src"],
    },
    "carnet": {
        "ios": [f"{CARNET_IOS}/CarnetApp", f"{CARNET_IOS}/Shared"],
        "android": [f"{REPO}/core/src/commonMain", f"{REPO}/carnet/shared/src", f"{REPO}/carnet/androidApp/src",
                    f"{REPO}/maurice/shared/src"],
    },
}

# Carnet's full scope only (CarnetScope.swift; parity.md): routes called from
# these files are not expected on Android.
CARNET_FULL_ONLY = re.compile(r"(LibraryView|ArticlesShelfView|AppShell|GardenView\.swift|UploadSection)")

# A route inside a string literal, after whatever host prefix it is glued to.
SWIFT_STR = re.compile(r'"(?:\\\([^)]*\))?(/(?:api|g|mcp|join)(?:/[^"\n]*)?)"')
KOTLIN_STR = re.compile(r'"(?:\$\{?[A-Za-z_.]*\}?)?(/(?:api|g|mcp|join)(?:/[^"\n]*)?)"')
# A base path kept in a name — `static var base: String { "\(CarnetAPI.baseURL)/api/v1/garden" }`,
# `private val base = "/api/v1/garden"`: its uses are resolved before routes are read.
SWIFT_BASE = re.compile(r'(?:let|var)\s+(\w+)\s*(?::\s*String)?\s*(?:=|\{)\s*"(?:\\\([^)]*\))?(/api[^"\\]*)"')
KOTLIN_BASE = re.compile(r'va[lr]\s+(\w+)\s*(?::\s*String)?\s*=\s*"(/api[^"$]*)"')
BASE = {"swift": SWIFT_BASE, "kotlin": KOTLIN_BASE}


def resolve(line: str, bases: dict, lang: str) -> str:
    for name, value in bases.items():
        if lang == "swift":
            line = re.sub(r"\\\((?:Self\.|self\.|\w+\.)?" + name + r"\)", lambda _: value, line)
        else:
            line = re.sub(r"\$\{?" + name + r"\b\}?", lambda _: value, line)
    return line


def normalise(path: str, lang: str):
    query = ""
    if lang == "swift":
        path = re.sub(r"\\\((?:[^()]|\([^()]*\))*\)", "{}", path)
    else:
        path = re.sub(r"\$\{[^}]*\}", "{}", path)
        path = re.sub(r"\$[A-Za-z_][A-Za-z0-9_.]*", "{}", path)
    if "?" in path:
        path, query = path.split("?", 1)
    path = re.sub(r"\{\}[^/]*", "{}", path).rstrip("/") or "/"
    params = sorted({p.split("=")[0] for p in query.split("&") if p and not p.startswith("{")})
    return path, params


def scan(roots, lang):
    found = {}
    ext = ".swift" if lang == "swift" else ".kt"
    pattern = SWIFT_STR if lang == "swift" else KOTLIN_STR
    for root in roots:
        for dirpath, _, files in os.walk(root):
            if "/build/" in dirpath + "/" or "Test" in dirpath:
                continue
            for f in files:
                if not f.endswith(ext):
                    continue
                full = os.path.join(dirpath, f)
                text = open(full, encoding="utf-8", errors="replace").read()
                bases = dict(BASE[lang].findall(text))
                for n, line in enumerate(text.splitlines(), 1):
                    if line.strip().startswith("//"):
                        continue
                    line = resolve(line, bases, lang)
                    for m in pattern.finditer(line):
                        path, params = normalise(m.group(1), lang)
                        entry = found.setdefault(path, {"params": set(), "where": []})
                        entry["params"].update(params)
                        entry["where"].append(f"{os.path.relpath(full, WORKSPACES)}:{n}")
    return found


def main():
    args = sys.argv[1:]
    apps = [args[args.index("--app") + 1]] if "--app" in args else list(APPS)
    as_json = "--json" in args
    report, missing_total = {}, 0
    for app in apps:
        ios = scan(APPS[app]["ios"], "swift")
        android = scan(APPS[app]["android"], "kotlin")
        missing, full_only, params = [], [], []
        for path, e in sorted(ios.items()):
            if path not in android:
                if app == "carnet" and all(CARNET_FULL_ONLY.search(w) for w in e["where"]):
                    full_only.append(path)
                else:
                    missing.append({"path": path, "where": e["where"][:3]})
            else:
                lost = sorted(e["params"] - android[path]["params"])
                if lost:
                    params.append({"path": path, "ios_params_not_on_android": lost})
        extra = sorted(p for p in android if p not in ios)
        missing_total += len(missing)
        report[app] = {"ios_routes": len(ios), "android_routes": len(android), "missing_on_android": missing,
                       "query_params_missing_on_android": params, "android_only": extra,
                       "carnet_full_scope_only": full_only}
    if as_json:
        print(json.dumps(report, indent=2, ensure_ascii=False))
    else:
        for app, r in report.items():
            print(f"── {app}: iOS calls {r['ios_routes']} routes, Android {r['android_routes']}")
            print(f"   called by iOS, never by Android ({len(r['missing_on_android'])}):")
            for m in r["missing_on_android"]:
                print(f"     {m['path']:<55} {m['where'][0]}")
            if r["query_params_missing_on_android"]:
                print("   query parameters iOS sends and Android does not:")
                for m in r["query_params_missing_on_android"]:
                    print(f"     {m['path']:<55} {', '.join(m['ios_params_not_on_android'])}")
            if r["android_only"]:
                print(f"   Android only ({len(r['android_only'])}): {', '.join(r['android_only'])}")
            if r["carnet_full_scope_only"]:
                print(f"   iOS full scope only, not expected: {len(r['carnet_full_scope_only'])}")
    sys.exit(1 if missing_total else 0)


if __name__ == "__main__":
    main()
