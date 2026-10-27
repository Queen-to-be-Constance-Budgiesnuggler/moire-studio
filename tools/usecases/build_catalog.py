#!/usr/bin/env python3
"""Builds app/src/main/assets/usecases.json: for each checklist test, the taps a person makes when carrying it out.

How: for every test in docs/studio-log.html, find the control labels (string literals in that room's Kotlin source) that the test's
steps name, and record them as 'tap:Room|Label'. A label is only a candidate if it appears in the steps as a whole phrase.
Hand-made entries in tools/usecases/overrides.json win over the generated ones (set a test to null to leave it out).

    python tools/usecases/build_catalog.py            # writes the catalog and tools/usecases/REVIEW.md
"""
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
LOG = os.path.join(ROOT, "docs", "studio-log.html")
SRC = os.path.join(ROOT, "app", "src", "main", "java", "dk", "cbavf", "studio")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "usecases.json")
OVERRIDES = os.path.join(os.path.dirname(__file__), "overrides.json")
REVIEW = os.path.join(os.path.dirname(__file__), "REVIEW.md")

# checklist room id -> (Room title the app reports, source folders holding its labels)
ROOMS = {
    "lib": ("Library", ["library"]), "draw": ("Draw", ["draw"]), "anim": ("Animate", ["animate"]),
    "seq": ("Sequence", ["sequence"]), "bridge": ("Bridge", ["bridge"]), "cam": ("Camera", ["camera"]),
    "loop": ("Loop", ["loop"]), "patch": ("Patch", ["patch"]), "dev": ("Develop", ["develop"]),
    "fft": ("FFT", ["fft"]), "glitch": ("Glitch", ["glitch"]), "comp": ("Compose", ["compose"]),
    "nodes": ("Node Lab", ["nodes"]), "rack": ("Node Lab", ["routing"]), "studio": ("Studio", ["studio"]),
    "deep": ("Develop", ["develop"]), "two": ("Node Lab", ["nodes"]),
    "n21cam": ("Camera", ["camera"]), "n21draw": ("Draw", ["draw", "abcompare"]), "n21cfg": ("Studio", ["studio", "feedback"]),
    "n21ch": ("Develop", ["develop"]), "n21ui": ("UI Editor", ["uieditor"]), "n21plg": ("Studio", ["studio", "plugins", "forge"]),
    "n21ab": ("Develop", ["develop", "abcompare"]), "n21slit": ("Slit-scan", ["slitscan"]), "n21sns": ("Node Lab", ["nodes"]),
    "n21fft": ("FFT", ["fft"]), "n21live": ("FFT", ["fft"]), "n21dbg": ("Studio", ["studio", "debug"]),
}
# Labels of tabs and rooms: opening them proves the area was visited, not that the test was done.
NAV = {"Studio", "Library", "Compose", "Node Lab", "Draw", "Animate", "Sequence", "Bridge", "Camera", "Loop", "Patch", "Develop", "FFT", "Glitch",
       "Slit-scan", "Module Forge", "Plugins", "UI Editor", "File", "Preferences", "Theme", "Config", "Notebook", "About", "Profile", "Front", "Back"}
GENERIC = {"Save", "Undo", "Redo", "Close", "Cancel", "Delete", "Reset", "Off", "On", "Add", "Remove", "Name", "Size", "Open", "Next"}
LIT = re.compile(r'"([^"\\\n$]{2,40})"')


# A string only counts as a tap target when it sits right after something clickable: a chip, button, menu item, toggle, tab or the
# studio back's button() helper. Headings, slider names and node names are plain Text and are never tapped.
CLICKABLE = re.compile(r"(?:ChoicePill|LiveInputButton|StraightToggle|Pill|FilterChip|AssistChip|SuggestionChip|InputChip|HoldChip|TextButton|OutlinedButton|FilledTonalButton|Button|DropdownMenuItem|Toggle|ChipRow|Chip|Tab|button|toggleButton)\s*\(")


def vocabulary(dirs):
    words = set()
    for d in dirs:
        base = os.path.join(SRC, d)
        for dp, _, files in os.walk(base):
            for f in files:
                if not f.endswith(".kt"):
                    continue
                text = open(os.path.join(dp, f), encoding="utf-8").read()
                for call in CLICKABLE.finditer(text):
                    # the strings right after the clickable call; stop at a blank line so an unrelated string is not picked up
                    window = text[call.end():call.end() + 420].split("\n\n")[0]
                    for m in LIT.findall(window)[:4]:
                        if re.match(r"^[A-Z0-9+\u2212\u27f2\u25b6\u2715\u21a9\u21aa\u270e].*", m) and "." not in m.rstrip(".\u2026") and not m.isupper():
                            words.add(m)
    return words


def load_tests():
    sys.path.insert(0, r"C:/Users/Avina/AppData/Local/Temp/claude/c--Users-Avina-Claude-Dev-moire-studio/c401623a-ea2b-45e9-9213-9c19e9ad33b3/scratchpad")
    s = open(LOG, encoding="utf-8").read()
    rooms = s[s.index("const ROOMS = ["):s.index("// Changelog, newest first")]
    pat = re.compile(r'^\s*\["([a-z0-9-]+)",\s*"((?:[^"\\]|\\.)*)",\s*"((?:[^"\\]|\\.)*)"\]', re.M)
    out = []
    for block in re.split(r"\n  \{ id: \"", rooms)[1:]:
        rid = block.split('"')[0]
        for tid, do, expect in pat.findall(block):
            out.append((rid, tid, do))
    return out


def phrase_in(label, text):
    return re.search(r"(?<![\w])" + re.escape(label) + r"(?![\w])", text) is not None


def main():
    vocab = {rid: vocabulary(dirs) for rid, (_, dirs) in ROOMS.items()}
    overrides = json.load(open(OVERRIDES, encoding="utf-8")) if os.path.exists(OVERRIDES) else {}
    tests, review = {}, ["# Use-case catalog review", "", "Generated by build_catalog.py. A test is 'seen' only when ALL its taps happen on one phone in one day.", ""]
    covered = 0
    items = load_tests()
    for rid, tid, do in items:
        if tid in overrides:
            if overrides[tid] is not None:
                tests[tid] = overrides[tid]
                covered += 1
                review.append(f"- {tid}: (override) {overrides[tid]['all']}")
            continue
        if rid not in ROOMS:
            continue
        title, _ = ROOMS[rid]
        found = []
        for label in sorted(vocab[rid], key=lambda x: -len(x)):
            if label in GENERIC and len(do) > 40:
                continue
            if phrase_in(label, do) and not any(label in f for f in found):
                found.append(label)
        if found:
            covered += 1
            steps = max(1, len([x for x in re.split(r"[;,]|then|→", do) if x.strip()]))
            # weak: only navigation labels were found, or fewer than half the steps are covered by a named control
            weak = all(l in NAV for l in found) or len(found) * 2 < steps
            spec = {"all": [f"tap:{title}|{l}" for l in found[:4]]}
            if weak:
                spec["weak"] = True
            tests[tid] = spec
            review.append(f"- {tid}{' (weak)' if weak else ''}: {', '.join(found[:4])}   <- {do[:90]}")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump({"version": 1, "note": "Generated by tools/usecases/build_catalog.py. Checklist test id -> triggers that must all be seen.", "tests": tests},
              open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    review.insert(3, f"{covered} of {len(items)} tests have triggers.\n")
    open(REVIEW, "w", encoding="utf-8").write("\n".join(review) + "\n")
    print(f"{covered} of {len(items)} tests have triggers -> {OUT}")


if __name__ == "__main__":
    main()
