# Agentic build session 2: from the pending list

Written 2026-10-23. Continues the build from [PENDING.md](PENDING.md). The first session's method is in
[AGENT-WORKFLOW.md](AGENT-WORKFLOW.md); this file only says what is different and what runs in which order.

## Roles
| Who | Does |
|---|---|
| Claude (this session) | Integration, Compose UI, Android APIs, reviews everything, installs on the S9+ and checks it |
| Local model (Qwen3-Coder 30B, `localhost:1234`) | Self-contained pure logic with a hand-written test that decides pass or fail. Runner: `tools/agent/run-local.ps1`, accept with `tools/agent/accept.sh` |
| The user | Hand-tests on the S21 while this runs, and decides |

## Phones
- **S9+** (100.111.15.33:5555) is the agent's phone: install, drive, screenshot, read logcat. Check the foreground app right
  before every tap, because the phone may be in use (on 2026-10-23 it showed a remote-desktop session).
- **S21** belongs to the user for testing. Do not install over it or drive it. Fresh builds go to the S9+ first; the user installs
  on the S21 when they choose.

## Rules for the whole session
1. No commit, push, publish or server change without the user saying so. Release notes stay neutral.
2. Every wave ends green: `testStudioDebugUnitTest`, `assembleStudioDebug`.
3. After a wave: install the studio build on the S9+, run the wave's check (below), and update `docs/PENDING.md`,
   `docs/TESTING.md`, and the studio log page with the result. Nothing is marked as passed unless it was seen on the phone.
4. Status to the user every 30 minutes while a wave is running, in two lines.
5. Ask only when a choice is the user's. Everything else gets a default, written down in the wave's notes.

## Waves, in order
Each wave lists the pending item, who builds it, and the check on the S9+.

**Wave 1: small clean-ups (item E, item D).**
- E, phone-agnostic: Claude. Remove Samsung and test-phone names from `app/src`; Bridge targets from intents and a user list in
  `user.profile`; `<queries>` by intent; memory limits from `ActivityManager.memoryClass`. Local: nothing (multi-file).
  Check: `grep -ri "samsung\|com\.sec\|S9+\|S21" app/src` is empty outside tests; Bridge room still lists installed editor apps.
- D, icon: Claude. Art inside the adaptive icon's safe zone, background from the same art. Check: screenshot of the launcher on the S9+.

**Wave 2: subtract blend (item B).**
- Local: `blend/SubtractMath.kt`, the per-channel maths for Subtract and ReverseSubtract with alpha and opacity, plus its test.
- Claude: the AGSL `RuntimeShader` in the compositor, the CPU export path, the Blend enum entries, the chip rows, Node Lab's blend node.
- Check: two known layers in Draw give the expected pixels on the S9+ screenshot; the saved PNG equals the preview.

**Wave 3: layout that scales and stays in bounds (item C).**
- Local: `ui/layout/WidthClass.kt` (compact, medium, expanded from dp width and height) and the clamping maths, plus tests.
- Claude: max widths for panels and slider tracks, the landscape side panel, the drag-to-scale edge, remembering the size in
  `user.workspace` per orientation, bounded dialogs. One question to settle first: should the number dialog open inside the
  control panel (my reading of the user's arrows)?
- Check: rotate the S9+ in Draw, Compose, Glitch and Develop; no control wider than its cap; the canvas keeps most of the screen.

**Wave 4: gates and envelopes (item A).**
- Local: `math/Gate.kt` (trigger to gate, gate to trigger, gate logic AND/OR/XOR/delay/divide/probability, sample and hold), plus tests.
- Claude: `SocketType.Gate`, the Node Lab nodes, the envelope graph control, "Add envelope" in the control overlay menu, Gate jacks.
- Check: in Node Lab a button trigger drives an envelope that moves a value; the value is visible on a Viewer node.

**Wave 5: finish what is half built.** Taken in this order, one at a time, each with its own check:
1. UI Editor layout and cables drive the real controls.
2. Channels: Split and Merge nodes, channel jacks on the rack.
3. A/B in Compose, FFT, Glitch, Animate and Camera.
4. History lists for Animate, Sequence and Develop.
5. Fringe predictor on screen.
6. Sensor bus: record and replay, seeds in Glitch and FFT.
7. Slit-scan from more sources.
8. Command palette and Quick Settings tile.

**Not in this session:** the scripting IDE, 3D, Rosetta Code, calculator, math and FM/AM modules, physics and vocabulary themes
(after the pilot, as planned).

## What counts as done for a wave
- Tests green, both flavors build.
- Installed on the S9+, check performed, screenshot or logcat line kept in `tasks/results/` or the studio log.
- PENDING, TESTING and the log page updated.
- A short report to the user: what changed, what was seen working, what was not tested.
