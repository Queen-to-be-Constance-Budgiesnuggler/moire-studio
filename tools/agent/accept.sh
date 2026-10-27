#!/usr/bin/env bash
# Copies a reviewed local-model card from its agent branch into the main working copy and marks it done.
# Usage: tools/agent/accept.sh T-002
# Reads files_out and test_dest from the card. Does not commit.
set -euo pipefail
id="$1"
repo="$(cd "$(dirname "$0")/../.." && pwd)"
card="$(ls "$repo"/tasks/"$id"-*.md | head -1)"
impl="$(grep -m1 '^files_out:' "$card" | sed -E 's/^files_out: *\[([^]]*)\].*/\1/' | tr -d ' ')"
test="$(grep -m1 '^test_dest:' "$card" | sed -E 's/^test_dest: *([^ #]*).*/\1/')"
for f in "$impl" "$test"; do
  mkdir -p "$repo/$(dirname "$f")"
  git -C "$repo" show "agent/$id:$f" > "$repo/$f"
  echo "copied $f"
done
sed -i -E "s/^status:.*/status: done                # reviewed and copied into the main working copy/" "$card"
