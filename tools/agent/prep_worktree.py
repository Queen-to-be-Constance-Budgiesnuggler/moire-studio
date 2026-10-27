#!/usr/bin/env python3
"""Gets a sprint worktree ready to build: copies local.properties (SDK path, feedback address) from the main repo if it is missing.

    python tools/agent/prep_worktree.py [worktree_dir]

Run from inside the worktree (or pass its path). Prints what it did; safe to run more than once.
"""
import os
import shutil
import sys

MAIN = "C:/Users/Avina/Claude_Dev/moire-studio"
wt = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.getcwd())
target = os.path.join(wt, "local.properties")
if os.path.exists(target):
    print("local.properties already there:", target)
elif os.path.abspath(wt) == os.path.abspath(MAIN):
    print("this is the main repo, nothing to do")
else:
    shutil.copyfile(os.path.join(MAIN, "local.properties"), target)
    print("copied local.properties into", wt)
