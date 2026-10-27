#!/usr/bin/env python3
"""Mirrors docs/PENDING.md into docs/studio-log.html as the page's PENDING data, and writes the plan front page's PLAN data.

    python tools/logpage/build_plan.py

Reads PENDING.md (checkbox items: [ ] open, [~] partly done, [x] done; the section is the nearest '## ' heading).
Replaces the block between the two PLAN-DATA markers in the log page. Edit PLAN below when the plan changes.
"""
import json
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PENDING = os.path.join(ROOT, "docs", "PENDING.md")
LOG = os.path.join(ROOT, "docs", "studio-log.html")
START, END = "// PLAN-DATA-START", "// PLAN-DATA-END"

PLAN = [
    {"phase": "Now (you)", "status": "active", "title": "Short test sessions on the phones",
     "items": [
         "Keep sessions short and close the app when done, so nothing runs in the background.",
         "Mark checks on the Test checklist. Badges there show what the phones' feedback says you already did.",
         "The S21 'not reacting' report, with logcat from the S21, when you have it.",
     ]},
    {"phase": "Sprint (scheduled workers)", "status": "next", "title": "Open build items, then the roadmap",
     "items": [
         "A coordinator runs every hour on the home PC (task MoireStudio-Sprint), hands items to builder and reviewer agents in their own git worktrees, merges into the sprint branch, runs all tests, and updates this page.",
         "When the Claude usage limit is reached it stops, and a resume task is set for the reset time.",
         "Its queue is below (docs/SPRINT.md); its run reports are in docs/SPRINT-REPORT.md.",
     ]},
    {"phase": "Your decisions", "status": "decide", "title": "Workers skip anything that needs these",
     "items": [
         "D1 Scripting language(s) for the IDE.",
         "D2 Sound: does the studio become a DAW too?",
         "D3 Vocabulary theme names, and where 'Afterpilot' fits.",
         "D4 Feedback before inviting testers: the first-run question, and who may read reports.",
         "D6 Merge sprint into main and put the repo on GitHub (lets cloud workers help).",
     ]},
]


def clean(text):
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)  # links -> their text
    text = text.replace("**", "").replace("`", "")
    return re.sub(r"\s+", " ", text).strip()


def parse():
    items, section, cur = [], "", None
    for line in open(PENDING, encoding="utf-8").read().split("\n"):
        if line.startswith("## "):
            section = line[3:].strip()
            cur = None
            continue
        m = re.match(r"^- \[( |x|~)\] (.*)$", line)
        if m:
            state = {" ": "open", "x": "done", "~": "partial"}[m.group(1)]
            cur = {"section": section, "state": state, "text": m.group(2)}
            items.append(cur)
            continue
        if cur is not None and line.startswith("      ") and line.strip():
            cur["text"] += " " + line.strip()
        elif line.strip() == "" or line.startswith("- ") or line.startswith("##"):
            cur = None if not line.startswith("      ") else cur
    for it in items:
        it["text"] = clean(it["text"])
    return items


SPRINT = os.path.join(ROOT, "docs", "SPRINT.md")


def parse_sprint():
    """The queue lines of docs/SPRINT.md: id, title, status, phase."""
    out, phase = [], ""
    if not os.path.exists(SPRINT):
        return out
    for line in open(SPRINT, encoding="utf-8").read().splitlines():
        if line.startswith("## "):
            phase = line[3:].strip()
            continue
        m = re.match(r"^- \[[ x~]\] \*\*([SR]\d+[a-z]?) (.+?)\*\*(.*)$", line)
        if not m:
            continue
        st = re.search(r"status:\s*([a-z-]+)", m.group(3))
        out.append({"id": m.group(1), "title": clean(m.group(2)), "status": st.group(1) if st else "todo", "phase": phase})
    return out


def main():
    items = parse()
    sprint = parse_sprint()
    counts = {s: sum(1 for i in items if i["state"] == s) for s in ("open", "partial", "done")}
    data = "\n".join([
        START,
        "const PLAN = " + json.dumps(PLAN, ensure_ascii=False, indent=1).replace("</", "<\\/") + ";",
        "const PENDING = " + json.dumps(items, ensure_ascii=False, indent=1).replace("</", "<\\/") + ";",
        "const PENDING_COUNTS = " + json.dumps(counts) + ";",
        "const SPRINT = " + json.dumps(sprint, ensure_ascii=False, indent=1).replace("</", "<\\/") + ";",
        END,
    ])
    html = open(LOG, encoding="utf-8").read()
    if START in html:
        html = re.sub(re.escape(START) + r".*?" + re.escape(END), lambda _: data, html, flags=re.S)
    else:
        html = html.replace("const LOG = [", data + "\n\nconst LOG = [", 1)
    open(LOG, "w", encoding="utf-8").write(html)
    print(f"{len(items)} PENDING items: {counts}; {len(sprint)} sprint items; {len(PLAN)} plan phases")


if __name__ == "__main__":
    main()
