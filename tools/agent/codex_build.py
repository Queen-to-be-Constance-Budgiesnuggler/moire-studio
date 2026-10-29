#!/usr/bin/env python3
"""Builds one sprint item with Codex (the user's ChatGPT plan) in its own git worktree, then tests and commits it.

    python tools/agent/codex_build.py S04 "<the item's full text>"

Prints one JSON line: branch, worktree, test result, and Codex's last message. The coordinator reviews and merges.
"""
import json
import os
import re
import shutil
import subprocess
import sys

MAIN = "C:/Users/Avina/Claude_Dev/moire-studio"
item_id, item_text = sys.argv[1], sys.argv[2]
branch = f"codex/{item_id}"
wt = os.path.join(MAIN, ".claude", "worktrees", f"codex-{item_id}")


def run(cmd, cwd, timeout=None, stdin=None):
    r = subprocess.run(cmd, cwd=cwd, input=stdin, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout, shell=isinstance(cmd, str))
    return r.returncode, (r.stdout or "") + (r.stderr or "")


# worktree: reuse it (a retry) or make a fresh one from sprint
if not os.path.isdir(wt):
    code, out = run(["git", "worktree", "add", "-B", branch, wt, "sprint"], MAIN)
    if code:
        print(json.dumps({"ok": False, "error": "worktree: " + out[-400:]}))
        sys.exit(1)
if not os.path.exists(os.path.join(wt, "local.properties")):
    shutil.copyfile(os.path.join(MAIN, "local.properties"), os.path.join(wt, "local.properties"))

# the builder rules are the sprint-builder agent's text, without its header
rules = open(os.path.join(MAIN, ".claude", "agents", "sprint-builder.md"), encoding="utf-8").read()
rules = re.sub(r"^---.*?---\s*", "", rules, flags=re.S)
prompt = (f"{rules}\n\n## Your item\n{item_id}: {item_text}\n\n"
          "Build it in this folder. Do not commit; the script commits. Finish with a short summary and the hand checks.")
last = os.path.join(wt, ".codex-last.txt")
CODEX = shutil.which("codex") or shutil.which("codex.cmd") or "codex"
# Codex's sandbox is read-only on Windows, so it runs unsandboxed; the prompt keeps it inside this worktree.
prompt += "\n\nWork only inside " + wt + ". Never push, never touch other folders, the phones or other machines."
code, out = run([CODEX, "exec", "--dangerously-bypass-approvals-and-sandbox", "--skip-git-repo-check", "-C", wt, "-o", last, "-"], wt,
                timeout=90 * 60, stdin=prompt)
summary = open(last, encoding="utf-8").read() if os.path.exists(last) else out[-1500:]
if os.path.exists(last):
    os.remove(last)

# judge by the test reports, never by Codex's own word
gcode, gout = run("gradlew.bat testStudioDebugUnitTest assembleStudioDebug --console=plain -q", wt, timeout=40 * 60)
tcode, tout = run([sys.executable, os.path.join(MAIN, "tools", "agent", "test_count.py"), wt], wt)
green = gcode == 0 and tcode == 0
run(["git", "add", "-A"], wt)
run(["git", "commit", "-q", "-m", f"{item_id}: built with Codex", "-m", "Co-Authored-By: Codex <noreply@openai.com>"], wt)
print(json.dumps({"ok": green, "branch": branch, "worktree": wt.replace("\\", "/"), "tests": tout.strip(),
                  "build_tail": "" if gcode == 0 else gout[-800:], "codex_exit": code, "summary": summary[-2500:]}))
