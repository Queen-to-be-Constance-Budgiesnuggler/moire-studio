#!/usr/bin/env python3
"""Turns docs/AUTO-EVIDENCE.json (from `server.py evidence`) into the write operations for the checklist page's `auto` store.

    python tools/usecases/evidence_batch.py > batch.json

Prints a JSON list of {"op": "set", "collection": "auto", "doc_id": <test id>, "data": {...}} for every test with some evidence.
The page shows them as badges; they never touch the ✓ / ✕ marks, which only a person sets.
"""
import json
import os
import sys
import time

path = os.path.join(os.path.dirname(__file__), "..", "..", "docs", "AUTO-EVIDENCE.json")
ev = json.load(open(path, encoding="utf-8"))["tests"]
ops = []
for tid, v in sorted(ev.items()):
    if v["state"] in ("none", "partial"):  # "started" is noise: every test that opens a tab would show it
        continue
    ops.append({"op": "set", "collection": "auto", "doc_id": tid, "data": {
        "a": v["state"], "n": v["installs"], "e": v["errors"], "c": v["crashes"],
        "t": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(v["last"])) if v["last"] else "",
    }})
json.dump(ops, sys.stdout, indent=1)
