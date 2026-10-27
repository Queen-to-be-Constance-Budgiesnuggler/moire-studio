# Agentic workflow: Claude + the home PC's local model

The pre-pilot plan (`PLAN-pre-pilot.md`, to be copied here from `~/.claude/plans/plan-out-this-next-jazzy-flamingo.md`) re-cut into **task cards** with an **executor** each, plus an automatic runner that works through the local ones. Status: **built and used on 2026-10-21** (results in section 8). The first build-out of the plan ran on it.

## 1. Who does what

| Executor | What it is | Good at | Not good at |
|---|---|---|---|
| `local` | Qwen3-Coder 30B-A3B in LM Studio, `localhost:1234`, model id `coder`, 16k context, ~13 tok/s on short prompts, ~5 tok/s on long ones (measured in the T-001 run) | Self-contained pure Kotlin (one file in, one file out), unit tests, data tables, doc and checklist text, boilerplate | Multi-file changes, Compose/Android APIs it has to guess at, anything needing the whole repo in view |
| `claude` | Claude Code session | Architecture, multi-file integration, Compose UI, gestures, camera/graphics pipeline, reviewing the local model's output | Nothing here, but it costs session tokens, so keep it for the hard parts |
| `user` | You | Decisions, phone testing on the S9+, pilot feedback | |

Rule of thumb for a card to be `local`: the spec fits in about 1,500 tokens, it touches one new file, it has a unit test that decides pass or fail, and it needs no Android UI code.

## 2. Task cards

One markdown file per task in `tasks/` (new folder at the repo root, committed). Format:

```markdown
---
id: T-005-step-math
executor: local            # local | claude | user
wave: 1
depends: []                # other task ids
files_out: [app/src/main/java/dk/cbavf/studio/math/Sliders.kt]
files_in:  [docs/AGENT-WORKFLOW.md]       # extra context pasted into the prompt, keep small
test: ./gradlew testDebugUnitTest --tests "dk.cbavf.studio.math.SlidersTest"
max_retries: 2
status: todo               # todo | running | needs-review | done | failed | blocked
---
## Spec
(plain description, signatures, edge cases, examples)

## Acceptance
(what the tests must prove)
```

Results are written to `tasks/results/<id>.md` (prompt, raw reply, parsed code, test output, retries, verdict).

## 3. The plan, re-cut into tasks

**Wave 0: infrastructure (Claude builds once, tonight's next step)**
- T-000 runner script, task card format, results folder, branch handling (section 4).

**Wave 1: `local` tasks (pure logic plus its tests; run automatically, overnight is fine)**
| Id | Task | From plan |
|---|---|---|
| T-001 | `math/Sliders.kt`: `fineStep`, grid rounding and clamping, log-mapping, plus `SlidersTest` | 5d |
| T-002 | `channels/ColorSpace.kt`: RGB↔CMYK, RGB↔Lab (D65), plus round-trip tests | 5b, 5c |
| T-003 | `math/Moire.kt` additions: beat period, fringe angle, plus tests (move existing `draw/model/MoireMath.kt` stays with Claude) | 5e-ii |
| T-004 | `math/Envelope.kt`: AD/AR/ADSR/AHDSR sampler with curve shapes, plus tests | gated triggers |
| T-005 | Gesture classifier (finger count, duration, movement thresholds → gesture enum), pure function, plus tests | gestures |
| T-006 | `LayerGroup` tree logic: `effectiveVisible`, duplicate with group-id remap, as pure functions over lists, plus tests | 2 |
| T-007 | `UserConfig` data model (`@Serializable`), defaults, JSON round trip, migration from a key-value map, plus tests | 3 |
| T-008 | `Debug` ring buffer and level filter (no Android classes), plus tests | 4 |
| T-009 | `overlay` blend maths helpers: fit-and-centre rect, alpha handling, quadrant hit-mapping for A/B, plus tests | 1, 5e |
| T-010 | `slitscan/model` slit sampling on synthetic frames, plus tests | slit-scan |
| T-011 | Undo history model: labelled entries, jump, branch truncation, plus tests | undo history |
| T-012 | Sensor filters: one-pole low-pass, high-pass, derivative, threshold→trigger, seed hashing, plus tests | sensors |

**Wave 1b: `local` documentation tasks** (drafts only, reviewed by Claude)
- T-020 new `ROOMS` items for `docs/studio-log.html` and matching `docs/TESTING.md` items for each feature built.
- T-021 `docs/ARCHITECTURE.md` sections §14 onward from the plan text.
- T-022 `docs/DEV-NOTES.md` and the PENDING banner text.
- Never marks anything as passed. Never renames existing ids.

**Wave 2: `claude` tasks (integration into the app; use the Wave 1 results)**
Slider widget (5d), Camera overlay burn-in (1), Draw group tabs UI (2), history panel, config tab and file migration (3), debug terminal view (4), Develop mixer and 3D channel picker (5b, 5c), A/B hold modifier, notification palette, sensor jacks, slit-scan room, UI Editor, Module Forge skeleton, feedback fields in `studio-log.html`.

**Wave 3: `user`**
Install on the S9+, run the checklist, write feedback in the new fields, decide the DEV-NOTES items.

**Not scheduled:** scripting IDE, 3D module, Rosetta Code, physics, FM/AM, calculator, math module, Afterpilot. They stay in the plan until a later session.

## 4. The automatic runner

A PowerShell script, `tools/agent/run-local.ps1` (to be written), run by hand or by Task Scheduler.

1. **Preflight.** Clear `ELECTRON_RUN_AS_NODE`. Check `localhost:1234/v1/models` is up with the model `coder`. If not, start LM Studio and `lms server start`, then `lms load qwen/qwen3-coder-30b --gpu 0.3 -c 16384 --identifier coder -y`. Stop if free disk on C: is under 3 GB.
2. **Pick the next card:** `executor: local`, `status: todo`, all `depends` done, lowest wave first.
3. **Isolate.** Work on a branch `agent/<id>` from the current `HEAD` (a git worktree, so your working copy and its uncommitted changes are never touched). No pushes. No commits to `main`.
4. **Prompt.** The master system prompt from the `lmstudio-coding-agent` skill, then `<<<AGENT>>>`, the card's spec, the contents of `files_in`, and the existing file if `files_out` already exists. Temperature 0.2, `max_tokens` about 2,000.
5. **Parse.** Take the first fenced code block and the `# SUMMARY:` line. If there is no code block, re-anchor ("output ONLY the code block") and retry.
6. **Write only allowed paths.** The runner writes the code to `files_out` and refuses any other path.
7. **Test.** Run the card's `test` command (Gradle unit test only; the runner uses the same JDK the project builds with, not the PATH's Java 11, and caps the run at 10 minutes). On failure, feed the compile or test error back to the model in the same conversation, up to `max_retries`.
8. **Record.** Write `tasks/results/<id>.md`, commit on the task branch (`agent: <id>`), set `status: needs-review` (tests green) or `failed` (retries used up). Then go to the next card.
9. **Stop conditions.** A card with no card left, two failures in a row, free disk below 3 GB, or the model server stops answering.

**Resource use.** One card at a time. The 30B model keeps about 7 GB of the 8 GB VRAM and ~19 GB of RAM, so a Gradle build runs alongside it, but nothing heavy (browser tabs, games) should. Measured speed is about 5 tokens/s on a 4,700-token prompt (13 on short ones), so a 150-line file (about 1,500 tokens) takes roughly 5 minutes to generate, plus the build.

**Automation triggers (pick one later):**
- Manual: `pwsh tools/agent/run-local.ps1`.
- Windows Task Scheduler: nightly, when you are away, and only on AC power.
- Claude Code `/loop` or a scheduled routine that starts the script, reads `tasks/results/`, and reviews the diffs. This is the "scheduled agentic workflow" item in DEV-NOTES, so research it in that session.

## 5. Review gate (how the token savings stay safe)

- Nothing from a `local` task reaches `main` unreviewed. Claude reads `tasks/results/<id>.md` and the branch diff (cheap: short files) and either merges (`done`), asks the local model to redo it with a better spec, or rewrites it.
- The local model's `# SUMMARY:` line is not trusted. It has been wrong while the code was right. The tests decide.
- Unit tests written by the same model that wrote the code can pass while testing the wrong thing. Claude spot-checks the test cases against the spec (the examples in the card are the ground truth).
- Wave 2 integrates the reviewed code. The branch is merged by Claude, not by the runner.
- A card that fails twice is promoted to `executor: claude`.

## 6. Guardrails

- Local model only gets the card text and the listed files. No secrets, no `local.properties`, no `user.profile`.
- Writes limited to `files_out`. No deletes. No network from the runner except `localhost:1234`.
- Max diff size per card, about 400 lines. Larger means the card should be split.
- The runner never touches the `main` working copy, which currently holds uncommitted changes (64 files at the time of writing).

## 7. First steps

1. Copy the plan into `docs/PLAN-pre-pilot.md` and link it from `PENDING.md`.
2. Claude writes T-000 (runner, card template, results folder) and tests it on one card (T-001, the smallest).
3. If T-001 passes the review gate, write cards T-002 to T-012 and let the runner do them overnight.
4. Wave 2 starts the next day, with the pure logic already done and tested.

## 8. What happened on the first real run (2026-10-21)

- **Runner:** `tools/agent/run-local.ps1` (several cards or `-Id` lists, `-WorkDir` for a second worktree so two runners work at once,
  skips a card another runner has taken); `tools/agent/accept.sh T-0nn` copies a reviewed card from its branch into the main copy.
  Cards are in `tasks/`, results in `tasks/results/`, a time-stamped log in `tasks/BUILD-LOG.txt`.
- **Speed:** about 5 tokens/s on long prompts, so 4 to 10 minutes per card including the build. Two runners in parallel finished
  the last four cards about twice as fast as one. The model server holds one copy of the model; a second copy loaded by a request that
  named the model another way had to be unloaded (it cost 6 GB of RAM).
- **Results:** 12 cards. 9 passed their tests (T-001 to T-005, T-008 to T-010, T-012), all but one on the first attempt after the
  spec asked for explicit imports and plain statements. 3 failed all three attempts and were written by hand against the same tests:
  T-006 (group logic: three deviations from the spec, found by the tests: unknown groups, kept parent and group, cycles), T-007 (config
  model: a wrong JSON import), T-011 (history: two declarations with one name).
- **Review found things the tests did not:** round-half-even instead of half-up (T-001, fixed and a test added), a missing locale on a
  number format (T-008), wildcard imports (T-003).
- **Lessons:** specs that list the exact imports and say "plain statements, no long one-line expressions" worked. Asking for long
  comments costs minutes of generation: ask for one-line comments. Hand-written tests are the judge; the model's own summary line
  is not trusted. The runner stops after two failures in a row, which on a bad stretch halts the queue: restart it on the rest.
- **Not done by the model:** anything that touches Compose, Android APIs or several files (the camera burn-in, Draw tabs, the UI
  Editor, the plugins, A/B, gestures, Config and so on). Those were written in the main session on top of the tested logic.
