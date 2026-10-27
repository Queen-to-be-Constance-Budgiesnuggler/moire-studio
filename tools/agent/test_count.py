#!/usr/bin/env python3
"""Counts the studio unit-test results from Gradle's XML reports, so a run is judged by the reports and not by a shell exit code.

    python tools/agent/test_count.py [repo_dir]

Prints "tests N failures F errors E" plus the failing classes, and exits 1 when anything failed or no report was found.
"""
import glob
import os
import re
import sys

repo = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "..")
files = glob.glob(os.path.join(repo, "app", "build", "test-results", "testStudioDebugUnitTest", "TEST-*.xml"))
tot = fail = err = 0
bad = []
for f in files:
    s = open(f, encoding="utf-8").read()
    m = re.search(r'tests="(\d+)" skipped="\d+" failures="(\d+)" errors="(\d+)"', s)
    if not m:
        continue
    t, fl, e = map(int, m.groups())
    tot += t
    fail += fl
    err += e
    if fl or e:
        bad.append(os.path.basename(f)[5:-4])
print(f"tests {tot} failures {fail} errors {err}" + (f" failing: {', '.join(bad)}" if bad else ""))
sys.exit(1 if (fail or err or tot == 0) else 0)
