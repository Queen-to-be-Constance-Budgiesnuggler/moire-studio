# Dev notes (pre-release)

## Decide before release
1. **Sound and a DAW mode.** Does sound processing join the studio, so it works as a DAW too? Questions to settle: the audio
   engine (Oboe / AAudio) and the latency on the S9+; whether audio is a new signal type in the Patch bay and Node Lab
   (`SocketType` already has Signal); a new room, or a module on the rack; how sonification of images and spectra feeds in.
   This is a decision, not a build task.
2. **Languages and stacks.** How many scripting languages to support (JavaScript, Lua, Python, C#, Rust, C++, and more through
   WebAssembly), and whether to ever rebuild or port the app to other stacks. Before any public release, recheck the Play Store
   rules about downloaded scripts, and the Rosetta Code licence (GFDL) if its content is mirrored.
3. **Philosophy session.** Define the application's manifesto in `docs/MANIFESTO.md`: the "unified frequency" idea (one software for every
   digital workflow, from simple harmonic to hypercomplex distorted signal control), and the Blender principle (a boilerplated
   scripting interface is what lets someone learn programming by changing a line).
4. **Naming and vocabulary session.** The naming theme is Moiré ("Moiréic", the default). At release a Vocabulary option in the
   Theme page can rename rooms, modules and terms: Gaussian, Bayesian, Fourieric, Euclidian, Pythagorean, Eulerian, Newtonic,
   non-Newtonic, non-Euclidian, and more over time. This needs every on-screen string moved into a keyed vocabulary layer, which
   also gives translations. (Note: "moiré" is the French word for watered silk, not a scientist.) Working names: **Module Forge**.
   Reserved: **Afterpilot**, a future module, plugin or extension, deliberately not tied to the calculator, math or FM/AM modules.
5. **A forked "Moiré game engine" branch**, at some undecided time: physics, 3D, scripting, gated triggers and envelopes, sensors
   and plugins as its base. Decide whether it is a branch, a fork or a separate app, after release.

## Scheduled agentic workflow (researched 2026-10-23; not set up yet)
Checked against the Claude Code docs (scheduled-tasks, routines, desktop-scheduled-tasks, headless, goal).

| Option | Runs on | Needs | Fits this project? |
|---|---|---|---|
| `/loop`, `CronCreate` | this PC, inside an open session | session idle; recurring jobs expire after 7 days; run late by up to 30 min; max 50 | Only for work while a session is open (what the queue used). Not a nightly job. |
| `/goal <condition>` | this PC, inside the session | a condition the transcript can prove ("all tests pass and QUEUE.md says finished"); put "or stop after N turns" in it | Fixes the "I have to keep it working" problem: the next turn starts by itself until the condition holds. Works with `claude -p`. |
| Desktop scheduled task | this PC, Claude Desktop app | app open and PC awake (missed runs: one catch-up on wake); minimum 1 hour in the form; local folder; optional Worktree | Best first choice: sees `tools/agent`, LM Studio, adb and the Android SDK. |
| `claude -p` from Windows Task Scheduler | this PC | nothing open; `--permission-mode dontAsk` or `auto` with `--permission-prompts none`, plus `--allowedTools`, `--max-budget-usd`, `--output-format json` | Good for a plain nightly test run; no UI to look at, so keep the prompt self-contained. |
| Cloud scheduled task (routine) | Anthropic cloud | a GitHub repo it can clone, minimum 1 hour, 100 scheduled runs/hour; pushes only to `claude/` branches | **Not yet:** this repo has no remote and most work is uncommitted. No phone, no LM Studio. Useful after the repo is on GitHub (PR review, release notes, docs drift). Whether the cloud image can build this Gradle project is unchecked. |
| GitHub Actions | GitHub runners | the same GitHub repo | Later, with the cloud option. |

**Important:** a Desktop task with Worktree on starts from the last commit (22bbce8), which lacks almost everything built since. Until the
work is committed (only when you say), run unattended jobs read-only in the working folder: tests, the build, consistency checks, a digest.

**Suggested first job (nightly, local):** run `./gradlew testStudioDebugUnitTest assembleStudioDebug`; check that every id in
`docs/TESTING.md` exists in `docs/studio-log.html` and the reverse; run `tools/agent/run-local.ps1` on cards marked `todo`; write
`docs/DIGEST.md` (what passed, what failed, what changed). It proposes, never commits, pushes, publishes or touches a phone.

**Set up 2026-10-23:** `tools/agent/nightly.ps1`, run by the Windows task `MoireStudio-Nightly` every day at 03:07 (runs only while you are logged in; the PC never sleeps on AC). It builds both flavours, runs the studio tests, lists cards marked todo, counts uncommitted files, and writes `docs/DIGEST.md` and `tasks/nightly.log`. On a failure it asks a read-only `claude -p` run for the likely cause. Left out for now: the TESTING/log id check (TESTING.md has no ids) and running the local-model cards. Stop it with `Unregister-ScheduledTask MoireStudio-Nightly`.

**Needs a person:** the hand checks V3 to V9, S21 logs, any commit, push or publish, release notes, and the decisions above.

## Reserved and working names
- Afterpilot (reserved), Module Forge (working), UI Editor (working).

## Quotes kept for the PR pipeline
See `PR-PIPELINE.md`.
