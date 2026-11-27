#!/usr/bin/env python3
"""The parity suite's UI layer: the real apps, driven the same way.

What the behavioural cases (parity/vectors) cannot reach — a flow written in
a SwiftUI view's body, a Compose screen — is compared here by driving the
installed apps: the iOS app on the parity simulator (through AXe, the tool
XcodeBuildMCP bundles), the Android app on an emulator (adb, uiautomator).
Both talk to the same fake household (parity/server). A scenario is a list of
steps a member would take; what is compared at each checkpoint is what the
household received since the previous one, and the texts the scenario
expects to see.

    python3 parity/ui/drive.py ios     <scenario.json>... # writes parity/ui/golden/<name>.json
    python3 parity/ui/drive.py android <scenario.json>... # compares against it

Scenario:
    {"name": "…", "app": "maurice" | "carnet", "about": "…",
     "routes": [ …fake household routes… ],
     "households": ["carnet-household"],            # + the routes of scenarios/common/<name>.json
     "ignore": ["re:/covers/"],                     # requests never compared (an image an app may cache)
     "steps": [
       {"see": "Enter a code or an address"},          # must be on screen (waits PARITY_UI_WAIT, 10 s)
       {"tap": "Enter a code or an address"},          # by visible text / accessibility label
       {"tap": "••••", "nth": 2},                      # the second of several (top to bottom; -1 or "last": true, the last)
       {"tap": "Back", "expect": "New conversation"},  # tapped again (twice at most) until what it opens shows
       {"tap": "Highlight", "hold": true},             # a long press
       {"tap": "Allow", "optional": true},             # a prompt that may not come
       {"tap": "“", "nudge": [40, 0]},                 # a control with no label, from its labelled neighbour
       {"tapxy": {"ios": [155, 598], "android": [400, 1500]}},  # a control with no label: by position
       {"type": "{base}"},                             # into the focused field; {base} = the household, {host} its host
       {"clear": true},                                # empties the focused field
       {"swipe": "<row text>", "to": "left"},          # a row slid aside
       {"scrollto": "My data", "within": 0.85},        # swipes until it is on screen, above that fraction of it
       {"scroll": "down"}, {"back": true}, {"wait": 2},
       {"include": "carnet-sign-in"},                  # the steps of scenarios/common/<name>.json
       {"forget": true},                               # the requests so far left out of the next checkpoint
       {"gone": "Connecting…"},                        # must leave the screen (waits)
       {"checkpoint": "paired",                        # the requests since the last checkpoint, the texts on screen
        "paths": ["/api/auth/*", "POST /api/x*"],      #   compared: only these (a screen's own loads aside)
        "accepted": {"kind": "wire", "why": "…"}}     #   this checkpoint's difference classified
     ]}

Any text may be {"ios": …, "android": …} where the two apps word the same
control differently (a finding); "nth" and "within" too. A step may carry
"only": "ios" | "android", or "skip": [platforms]. Requests are compared as a
multiset (the apps fire reads side by side). A scenario-level "accepted"
covers every checkpoint and lets Android stop short (a screen it has not got
yet) without failing. A checkpoint marked accepted whose sides agree fails:
drop the mark.

The household's address differs by platform: 127.0.0.1 from the simulator,
10.0.2.2 from the emulator (debug builds allow plain http to it).
"""
import glob
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = os.environ.get("PARITY_PORT", "47800")
CONTROL = f"http://127.0.0.1:{PORT}"
APPS = {
    "maurice": {"ios": "eu.chezmaurice.maurice", "android": os.environ.get("PARITY_MAURICE_APK_ID", "eu.chezmaurice.maurice.debug")},
    "carnet": {"ios": "eu.chezmaurice.carnet.app", "android": os.environ.get("PARITY_CARNET_APK_ID", "eu.chezmaurice.carnet.debug")},
}
# How long a step waits for its text (s): longer on a loaded machine.
WAIT = float(os.environ.get("PARITY_UI_WAIT", "10"))
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")


def axe_path():
    if os.environ.get("AXE"):
        return os.environ["AXE"]
    found = sorted(glob.glob(os.path.expanduser("~/.npm/_npx/*/node_modules/xcodebuildmcp/bundled/axe")))
    if not found:
        sys.exit("AXe not found: install XcodeBuildMCP (npx xcodebuildmcp) or set AXE")
    return found[-1]


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


# ── The fake household ─────────────────────────────────────

def household(path, body=None):
    req = urllib.request.Request(CONTROL + path, method="POST" if body is not None else "GET",
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"content-type": "application/json"})
    with urllib.request.urlopen(req, timeout=5) as r:
        return json.loads(r.read() or b"null")


# ── iOS: the parity simulator, through AXe ─────────────────

class IOS:
    name = "ios"
    host = "127.0.0.1"
    base = f"http://{host}:{PORT}"

    def __init__(self):
        self.axe = axe_path()
        self.udid = os.environ.get("PARITY_SIM") or self._sim(os.environ.get("PARITY_SIM_NAME", "Parity iPhone"))

    @staticmethod
    def _sim(name):
        devices = json.loads(run(["xcrun", "simctl", "list", "devices", "-j"]).stdout)["devices"]
        for devs in devices.values():
            for d in devs:
                if d["name"] == name and d["isAvailable"]:
                    return d["udid"]
        sys.exit(f"no simulator named {name!r}")

    def fresh(self, app):
        bundle = APPS[app]["ios"]
        # A system prompt left by the previous run outlives the app: answer it.
        for label, x, y, _ in self.elements():
            if label in ("Don’t Allow", "Don't Allow"):
                self.tap_at(x, y)
                time.sleep(1)
                break
        run(["xcrun", "simctl", "terminate", self.udid, bundle])
        # A blank device: the app's data and the keychain (tokens survive an uninstall).
        container = run(["xcrun", "simctl", "get_app_container", self.udid, bundle, "data"]).stdout.strip()
        # Its app groups too (Carnet keeps its households there, for the share extension).
        groups = [l.split("\t", 1) for l in run(["xcrun", "simctl", "get_app_container", self.udid, bundle, "groups"]).stdout.splitlines() if "\t" in l]
        domains = [(container, bundle)] if container else []
        domains += [(path.strip(), group) for group, path in groups]
        for d, domain in domains:
            # Through the preferences daemon first: it keeps what it read, and
            # writes it back over a deleted file.
            run(["xcrun", "simctl", "spawn", self.udid, "defaults", "delete", os.path.join(d, "Library/Preferences", domain)])
            for sub in ("Library/Preferences", "Library/Application Support", "Documents", "Library/Caches", "queue"):
                run(["rm", "-rf", os.path.join(d, sub)])
        run(["xcrun", "simctl", "keychain", self.udid, "reset"])
        run(["xcrun", "simctl", "launch", self.udid, bundle, "-AppleLanguages", "(en)", "-AppleLocale", "en_US"])
        time.sleep(3)

    def elements(self):
        # describe-ui fails now and then on a loaded machine: a few tries.
        for _ in range(4):
            res = run([self.axe, "describe-ui", "--udid", self.udid])
            try:
                tree = json.loads(res.stdout)
                break
            except json.JSONDecodeError:
                if os.environ.get("PARITY_DEBUG"):
                    print(f"  describe-ui failed: {res.stderr.strip()[:200]}", file=sys.stderr)
                time.sleep(1)
        else:
            return []
        found = []

        def walk(n):
            # The software keyboard's keys are not the app's.
            if str(n.get("AXUniqueId") or "").startswith("UIKeyboard"):
                return
            for key in ("AXLabel", "AXValue", "title"):
                v = n.get(key)
                if isinstance(v, str) and v.strip():
                    f = n.get("frame") or {}
                    found.append((v.strip(), f.get("x", 0) + f.get("width", 0) / 2, f.get("y", 0) + f.get("height", 0) / 2, n.get("type") or n.get("role")))
            for c in n.get("children") or []:
                walk(c)
        for n in tree if isinstance(tree, list) else [tree]:
            walk(n)
        # The system's notification prompt (after the first sign-in) is not
        # the app's: declined, as a member who has not decided yet would.
        if any(label.endswith("Would Like to Send You Notifications") for label, *_ in found):
            for label, x, y, _ in found:
                if label in ("Don’t Allow", "Don't Allow"):
                    self.tap_at(x, y)
                    time.sleep(1)
                    return self.elements()
        return found

    def stop(self, app):
        run(["xcrun", "simctl", "terminate", self.udid, APPS[app]["ios"]])

    def screenshot(self, path):
        # What the screen showed when a step failed, for the eye.
        run(["xcrun", "simctl", "io", self.udid, "screenshot", path])

    def tap_at(self, x, y):
        # A real touch (down, up): the simulator's own tap is now and then
        # lost on a toolbar button or right after a screen change.
        run([self.axe, "tap", "-x", str(int(x)), "-y", str(int(y)), "--tap-style", "physical", "--udid", self.udid])

    def type(self, text):
        run([self.axe, "type", text, "--udid", self.udid])

    def clear(self):
        # Select all (Cmd-A), then delete: the focused field is empty.
        run([self.axe, "key-combo", "--modifiers", "227", "--key", "4", "--udid", self.udid])
        run([self.axe, "key", "42", "--udid", self.udid])

    def hold_at(self, x, y):
        run([self.axe, "touch", "-x", str(int(x)), "-y", str(int(y)), "--down", "--up", "--delay", "1.2", "--udid", self.udid])

    def screen(self):
        out = run([self.axe, "describe-ui", "--udid", self.udid]).stdout
        try:
            f = json.loads(out)[0]["frame"]
            return f["width"], f["height"]
        except (json.JSONDecodeError, IndexError, KeyError, TypeError):
            return 402, 874

    def swipe(self, x1, y1, x2, y2):
        run([self.axe, "swipe", "--start-x", str(int(x1)), "--start-y", str(int(y1)),
             "--end-x", str(int(x2)), "--end-y", str(int(y2)), "--duration", "0.4", "--udid", self.udid])

    def swipe_at(self, x, y, direction):
        w, _ = self.screen()
        dx = 220 if direction == "right" else -220
        self.swipe(x, y, max(5, min(w - 5, x + dx)), y)

    def back(self):
        # No back key: the edge swipe every navigation stack answers.
        w, h = self.screen()
        run([self.axe, "gesture", "swipe-from-left-edge", "--screen-width", str(int(w)), "--screen-height", str(int(h)), "--udid", self.udid])

    def scroll(self, direction):
        # scroll-up moves the content up: what a member does to see further down.
        w, h = self.screen()
        preset = {"down": "scroll-up", "up": "scroll-down"}[direction]
        run([self.axe, "gesture", preset, "--screen-width", str(int(w)), "--screen-height", str(int(h)), "--udid", self.udid])


# ── Android: an emulator, through adb ──────────────────────

class Android:
    name = "android"
    host = "10.0.2.2"
    base = f"http://{host}:{PORT}"

    def __init__(self):
        self.serial = os.environ.get("ANDROID_SERIAL") or next(
            (l.split()[0] for l in run([ADB, "devices"]).stdout.splitlines()[1:] if l.strip().endswith("device")), None)
        if not self.serial:
            sys.exit("no emulator attached (adb devices)")

    def adb(self, *args):
        return run([ADB, "-s", self.serial, *args])

    def fresh(self, app):
        pkg = APPS[app]["android"]
        # A blank device is blank in both apps: they share their households
        # (SharedPairings), and what the other kept would come back here.
        for each in APPS.values():
            self.adb("shell", "pm", "clear", each["android"])
        # No system prompt in the way (iOS's is declined as it shows).
        self.adb("shell", "pm", "grant", pkg, "android.permission.POST_NOTIFICATIONS")
        # Its launcher activity, started explicitly (monkey refuses on this image).
        activity = self.adb("shell", "cmd", "package", "resolve-activity", "--brief", pkg).stdout.strip().splitlines()[-1]
        if "/" not in activity:
            sys.exit(f"{pkg} is not installed on {self.serial}")
        # A loaded emulator leaves « … isn't responding » dialogs about: wait them out.
        for label, x, y, _ in self.elements():
            if label == "Wait":
                self.tap_at(x, y)
                time.sleep(1)
        self.adb("shell", "am", "start", "-W", "-n", activity)
        time.sleep(3)

    def elements(self):
        self.adb("shell", "uiautomator", "dump", "/sdcard/parity-ui.xml")
        xml = self.adb("exec-out", "cat", "/sdcard/parity-ui.xml").stdout
        try:
            root = ET.fromstring(xml)
        except ET.ParseError:
            return []
        found = []
        for n in root.iter("node"):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
            if not m:
                continue
            x1, y1, x2, y2 = map(int, m.groups())
            for key in ("text", "content-desc"):
                v = (n.get(key) or "").strip()
                if v:
                    found.append((v, (x1 + x2) / 2, (y1 + y2) / 2, n.get("class")))
        return found

    def stop(self, app):
        self.adb("shell", "am", "force-stop", APPS[app]["android"])

    def screenshot(self, path):
        # What the screen showed when a step failed, for the eye.
        with open(path, "wb") as f:
            f.write(subprocess.run([ADB, "-s", self.serial, "exec-out", "screencap", "-p"], capture_output=True).stdout)

    def tap_at(self, x, y):
        self.adb("shell", "input", "tap", str(int(x)), str(int(y)))

    def type(self, text):
        # `input text` takes no spaces as such; %s is its space. A few letters
        # at a time: a busy emulator drops what comes while a field recomposes.
        for i in range(0, len(text), 3):
            chunk = text[i:i + 3].replace(" ", "%s")
            self.adb("shell", "input", "text", chunk.replace("'", "\\'"))
            time.sleep(0.3)

    def clear(self):
        # To the end, then delete back: the focused field is empty (Ctrl-A
        # does not select in a Compose password field).
        self.adb("shell", "input", "keyevent", "123", *(["67"] * 40))

    def hold_at(self, x, y):
        self.adb("shell", "input", "swipe", str(int(x)), str(int(y)), str(int(x)), str(int(y)), "1200")

    def screen(self):
        m = re.search(r"(\d+)x(\d+)", self.adb("shell", "wm", "size").stdout)
        return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)

    def swipe(self, x1, y1, x2, y2):
        self.adb("shell", "input", "swipe", str(int(x1)), str(int(y1)), str(int(x2)), str(int(y2)), "400")

    def swipe_at(self, x, y, direction):
        w, _ = self.screen()
        x2 = min(w - 10, x + w * 0.6) if direction == "right" else max(10, x - w * 0.6)
        self.swipe(x, y, x2, y)

    def back(self):
        self.adb("shell", "input", "keyevent", "KEYCODE_BACK")

    def scroll(self, direction):
        w, h = self.screen()
        y1, y2 = (h * 0.7, h * 0.3) if direction == "down" else (h * 0.3, h * 0.7)
        self.adb("shell", "input", "swipe", str(w // 2), str(int(y1)), str(w // 2), str(int(y2)), "300")


# ── Running a scenario ─────────────────────────────────────

def find(driver, text, timeout=10.0, nth=1):
    """Where `text` is on screen: the nth of several, top to bottom (-1: the
    last — a dialog's button over the control that raised it comes after it)."""
    end = time.time() + timeout
    while True:
        elements = driver.elements()
        hits = []  # one per place: iOS reports a button and its group at the same spot
        for label, x, y, _ in elements:
            # (0, 0): a frame not laid out yet (a menu opening).
            if label == text and (x, y) != (0, 0) and all(abs(x - hx) > 2 or abs(y - hy) > 2 for hx, hy in hits):
                hits.append((x, y))
        if len(hits) >= abs(nth):
            return hits[nth - 1] if nth > 0 else hits[nth]
        # A loaded emulator's « … isn't responding »: wait it out, it is not the app's answer.
        if any(label.endswith("isn't responding") for label, *_ in elements):
            for label, x, y, _ in elements:
                if label == "Wait":
                    driver.tap_at(x, y)
        if time.time() > end:
            return None
        time.sleep(0.5)


def texts(driver):
    return sorted({label for label, *_ in driver.elements()})


def requests():
    # The sockets reconnect on their own; never part of what is compared.
    return [r for r in household("/__parity/log") if not r["path"].endswith("/ws")]


def common(name):
    return json.load(open(f"{HERE}/scenarios/common/{name}.json", encoding="utf-8"))


def expand(scenario):
    """Its routes, then those of the households it names (`"households": [name]`,
    from scenarios/common/: the scenario's own routes answer first); its steps,
    with each `{"include": name}` replaced by that file's steps."""
    routes = list(scenario.get("routes", []))
    for name in scenario.get("households", []):
        routes += common(name).get("routes", [])
    steps = []
    for step in scenario["steps"]:
        if "include" in step:
            steps += expand(common(step["include"]) | {"households": []})[1]
        else:
            steps.append(step)
    return routes, steps


def play(driver, scenario):
    routes, steps = expand(scenario)
    household("/__parity/reset", {"routes": routes})
    driver.fresh(scenario["app"])
    out = {"checkpoints": [], "failures": []}
    seen = 0

    def value(v):
        # A text the two apps word differently: {"ios": "…", "android": "…"}.
        v = v.get(driver.name) if isinstance(v, dict) else v
        return v.replace("{base}", driver.base).replace("{host}", driver.host) if isinstance(v, str) else v

    def per(step, key, default):
        v = step.get(key, default)
        return v.get(driver.name, default) if isinstance(v, dict) else v

    for i, step in enumerate(steps):
        if step.get("only", driver.name) != driver.name or driver.name in step.get("skip", []):
            continue
        nth = -1 if step.get("last") else per(step, "nth", 1)
        if "see" in step:
            want = value(step["see"])
            if find(driver, want, step.get("timeout", WAIT)) is None:
                out["failures"].append(f"step {i}: never saw {want!r} — on screen: {texts(driver)}")
        elif "gone" in step:
            want = value(step["gone"])
            end = time.time() + step.get("timeout", WAIT)
            while find(driver, want, 0) is not None and time.time() < end:
                time.sleep(0.5)
            if find(driver, want, 0) is not None:
                out["failures"].append(f"step {i}: {want!r} stayed on screen")
        elif "tap" in step:
            want = value(step["tap"])
            at = find(driver, want, step.get("timeout", 3 if step.get("optional") else WAIT), nth)
            if at is None:
                if step.get("optional"):
                    continue
                out["failures"].append(f"step {i}: nothing to tap called {want!r} — on screen: {texts(driver)}")
                driver.screenshot(f"{HERE}/build/{scenario['name']}.{driver.name}.png")
                break
            # Never on something still moving (a sheet rising): where it is twice running.
            for _ in range(5):
                again = find(driver, want, 2, nth)
                if again is None or (abs(again[0] - at[0]) <= 2 and abs(again[1] - at[1]) <= 2):
                    break
                at = again
            nudge = value(step.get("nudge")) if step.get("nudge") else None
            if nudge:
                at = (at[0] + nudge[0], at[1] + nudge[1])
            if step.get("hold"):
                driver.hold_at(*at)
            else:
                driver.tap_at(*at)
            time.sleep(step.get("pause", 1))
            # "expect": what the tap opens. A tap that lands while a screen is
            # still settling is lost: tapped again, twice at most.
            if step.get("expect"):
                for _ in range(2):
                    if find(driver, value(step["expect"]), 4) is not None:
                        break
                    again = find(driver, want, 0, nth)
                    if again:
                        driver.tap_at(*again)
                        time.sleep(step.get("pause", 1))
        elif "tapxy" in step:
            # A control with no text nor label (a colour swatch): where it is, per platform.
            x, y = step["tapxy"][driver.name]
            driver.tap_at(x, y)
            time.sleep(step.get("pause", 1))
        elif "swipe" in step:
            want = value(step["swipe"])
            at = find(driver, want, step.get("timeout", WAIT))
            if at is None:
                out["failures"].append(f"step {i}: nothing to swipe called {want!r} — on screen: {texts(driver)}")
                break
            driver.swipe_at(*at, step.get("to", "left"))
            time.sleep(step.get("pause", 1))
        elif "type" in step:
            driver.type(value(step["type"]))
            time.sleep(0.5)
        elif "clear" in step:
            driver.clear()
            time.sleep(0.5)
        elif "scrollto" in step:
            # Scrolls (the content up, as a thumb does) until it is well on
            # screen. "within": how far down the screen counts (0.5 with the
            # keyboard up). The thumb moves in the upper half, clear of a keyboard.
            want = value(step["scrollto"])
            w, h = driver.screen()
            within = per(step, "within", 0.85)
            for _ in range(step.get("swipes", 10)):
                at = find(driver, want, 0)
                if at and h * 0.12 < at[1] < h * within:
                    break
                driver.swipe(w / 2, h * 0.55, w / 2, h * 0.25)
                time.sleep(1)
            else:
                out["failures"].append(f"step {i}: never scrolled to {want!r} — on screen: {texts(driver)}")
                break
        elif "back" in step:
            driver.back()
            time.sleep(step.get("pause", 1))
        elif "scroll" in step:
            driver.scroll(step["scroll"])
            time.sleep(step.get("pause", 1))
        elif "wait" in step:
            time.sleep(step["wait"])
        elif "forget" in step:
            # What came before is not this scenario's subject (a shared way in):
            # left out of the next checkpoint.
            time.sleep(step.get("settle", 2))
            seen = len(requests())
        elif "checkpoint" in step:
            time.sleep(step.get("settle", 1.5))
            # The requests since the previous checkpoint.
            log = requests()
            out["checkpoints"].append({"name": step["checkpoint"], "requests": log[seen:], "texts": texts(driver)})
            seen = len(log)
    # Left running, the app keeps polling the household the next scenario
    # (or the other platform's run) is reading.
    driver.stop(scenario["app"])
    return out


def matches(pattern, request):
    """A request named the way routes name paths: exact, "/api/x*" (prefix) or
    "re:…", after an optional method ("POST /api/x")."""
    path = request["path"]
    method, _, rest = pattern.partition(" ")
    if rest:
        if request["method"] != method:
            return False
        pattern = rest
    if pattern.startswith("re:"):
        return re.search(pattern[3:], path) is not None
    if pattern.endswith("*"):
        return path.startswith(pattern[:-1])
    return path == pattern


def compare(want, got, scenario):
    """What Android does differently: the requests at each checkpoint, as a
    multiset (the texts are kept for the eye). Returns [(difference, accepted)],
    accepted being the checkpoint's or the scenario's mark, or None.

    "ignore" (the scenario's and its households') leaves requests out; a
    checkpoint's "paths" keeps only those (a screen reached at the end loads a
    dozen things of its own, compared by their own scenarios)."""
    ignore = list(scenario.get("ignore", []))
    for name in scenario.get("households", []):
        ignore += common(name).get("ignore", [])
    _, steps = expand(scenario)
    marks = {s["checkpoint"]: s for s in steps if "checkpoint" in s}
    key = lambda r: json.dumps({k: r.get(k) for k in ("method", "path", "query", "auth", "body")}, sort_keys=True, ensure_ascii=False)
    diffs = []
    for w, g in zip(want["checkpoints"], got["checkpoints"]):
        step = marks.get(w["name"], {})
        paths = step.get("paths", scenario.get("paths"))
        keep = lambda rs: Counter(key(r) for r in rs
                                  if not any(matches(p, r) for p in ignore)
                                  and (not paths or any(matches(p, r) for p in paths)))
        wr, gr = keep(w["requests"]), keep(g["requests"])
        accepted = step.get("accepted") or scenario.get("accepted")
        if wr != gr:
            only_w, only_g = sorted((wr - gr).elements()), sorted((gr - wr).elements())
            diffs.append((f"checkpoint {w['name']}: requests differ\n    only on iOS     {only_w}\n    only on Android {only_g}", accepted))
        elif step.get("accepted"):
            diffs.append((f"checkpoint {w['name']}: marked accepted ({step['accepted'].get('kind')}) but both sides agree: drop the mark", None))
    if len(want["checkpoints"]) != len(got["checkpoints"]):
        diffs.append((f"{len(want['checkpoints'])} checkpoints on iOS, {len(got['checkpoints'])} on Android", scenario.get("accepted")))
    return diffs


def text_notes(want, got):
    """Texts on screen on one side only, per checkpoint: for the eye, never a failure
    (layouts differ: icons' names, scroll bars, a sheet's handle)."""
    notes = []
    for w, g in zip(want["checkpoints"], got["checkpoints"]):
        only_ios = sorted(set(w["texts"]) - set(g["texts"]))
        only_android = sorted(set(g["texts"]) - set(w["texts"]))
        if only_ios or only_android:
            notes.append(f"{w['name']}: only on iOS {only_ios} / only on Android {only_android}")
    return notes


def main():
    if len(sys.argv) < 3 or sys.argv[1] not in ("ios", "android"):
        sys.exit(__doc__)
    platform, files = sys.argv[1], sys.argv[2:]
    driver = IOS() if platform == "ios" else Android()
    os.makedirs(f"{HERE}/golden", exist_ok=True)
    os.makedirs(f"{HERE}/build", exist_ok=True)
    status = 0
    for path in files:
        scenario = json.load(open(path, encoding="utf-8"))
        name = scenario["name"]
        got = play(driver, scenario)
        for f in got["failures"]:
            # A scenario marked as a whole (a screen Android has not got yet)
            # may stop short on Android: said, not failed.
            if platform == "android" and scenario.get("accepted"):
                print(f"· {name} [android] stopped ({scenario['accepted'].get('kind')}): {f[:300]}")
                continue
            print(f"✗ {name} [{platform}] {f}")
            status = 1
        if platform == "ios":
            json.dump(got, open(f"{HERE}/golden/{name}.json", "w", encoding="utf-8"), indent=2, ensure_ascii=False, sort_keys=True)
            print(f"{'✓' if not got['failures'] else '✗'} {name} [ios] golden written")
            continue
        json.dump(got, open(f"{HERE}/build/{name}.android.json", "w", encoding="utf-8"), indent=2, ensure_ascii=False, sort_keys=True)
        golden = f"{HERE}/golden/{name}.json"
        if not os.path.exists(golden):
            print(f"✗ {name}: no iOS golden — run with ios first"); status = 1; continue
        want = json.load(open(golden, encoding="utf-8"))
        diffs = compare(want, got, scenario)
        for note in text_notes(want, got):
            print(f"  · {name} texts — {note}")
        unclassified = [d for d, a in diffs if not a]
        classified = [a.get("kind") for d, a in diffs if a]
        if unclassified:
            status = 1
            print(f"✗ {name} [android] differs from iOS:\n  " + "\n  ".join(unclassified))
        if classified:
            print(f"· {name} [android] accepted: " + ", ".join(f"{k} ×{classified.count(k)}" for k in sorted(set(classified))))
        if not diffs and not got["failures"]:
            print(f"✓ {name} [android] agrees with iOS")
    sys.exit(status)


if __name__ == "__main__":
    main()
