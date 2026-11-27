#!/usr/bin/env python3
"""The iOS bugs the parity suite found: every case marked `ios-bug`, grouped
by its reason, with the GitHub issue (or private security advisory) it is
filed as: the `issue` of its mark.

    python3 parity/ios-bugs.py
"""
import collections
import glob
import json
import os

root = os.path.dirname(os.path.abspath(__file__))
groups = collections.OrderedDict()
for path in sorted(glob.glob(f"{root}/vectors/*/*.json")):
    app = path.split("/")[-2]
    doc = json.load(open(path, encoding="utf-8"))
    for case in doc["cases"]:
        mark = case.get("accepted")
        if isinstance(mark, dict) and mark.get("kind") == "ios-bug":
            groups.setdefault((app, mark.get("why", ""), mark.get("issue", "no issue yet")), []).append(f"{doc['domain']} / {case['name']}")

for (app, why, issue), cases in groups.items():
    print(f"- [{app}] {why}\n  {issue}")
    print(f"  ({len(cases)} case{'s' if len(cases) > 1 else ''}: {'; '.join(cases[:4])}{' …' if len(cases) > 4 else ''})")
print(f"\n{sum(len(c) for c in groups.values())} cases, {len(groups)} distinct reasons")
