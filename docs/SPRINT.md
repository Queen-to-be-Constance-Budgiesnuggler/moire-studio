# Sprint to the pilot

The work queue for the scheduled workers (see "How the sprint runs" at the end). One line per item. The coordinator sets
`status:` to `todo`, `active`, `review`, `done`, `needs-fix` or `blocked`, and writes a one-line result under an item when it
finishes. Items are taken in order, skipping ones whose `depends:` are not done. An item that is too big is split into
`a`, `b`, ... lines in place. Nothing here is ticked off by a person's hand test: the checklist page does that.

Rules for every item: branch `sprint` only (never `main`), no push, no server or phone changes, unit tests for any logic,
`./gradlew.bat testStudioDebugUnitTest assembleStudioDebug` green before it counts as done, the docs and
the checklist page updated with new hand checks.

## Phase 1: open build items (pilot first)

- [x] **S01 Nothing runs while the studio is closed** · status: done · size: M · depends: none
  The S9+ drained (416 mAh, 70 min CPU with the screen off). Audit every loop, coroutine, socket, sensor listener and timer that can
  run while the activity is stopped (OscBus, OscOutBus, SensorBus/SensorHub, LoopEngine, Node Lab Live, Patch live, UI cables runtime,
  GateBank clock, ModulationDriver, palette, feedback checkpoint, Router publishing) and make each pause on ON_STOP and resume on
  ON_START through one lifecycle flag. Done when: a unit-tested flag/logic, each loop checks it, and the report lists every loop found.
  Done 2026-10-26 (retry, run 0451): `foreground/RunGate` is the one flag (MainActivity sets it on ON_START and clears it on ON_STOP;
  `sleep` holds while off screen; hooks let go of the sensors, the mic and the link). Every loop in the builder's table pauses: OSC in
  and out, MIDI in and out, SensorHub and SensorBus, LoopEngine, Node Lab Live, Patch live, PatchSignalBus, plugin behaviours, UI cables,
  GateBank, ModulationDriver, the Sequence projector, Animate, Library flicker, the feedback checkpoint. The earlier stopgap pauses
  (MIDI, plugins, sensors, Loop) were replaced by the gate. Suite 1046 tests, 0 failures at merge. Left: the camera preview and the
  screen-recording service are not gated; battery drain not measured on a phone.
- [x] **S02 Main-thread work that can freeze the app** · status: done · size: M · depends: S01
  The S9+ reported "stopped responding" at 23:27 after Camera and Library use. Find bitmap decodes, file reads/writes, JSON and
  lineage work on the main thread in Camera, Library, Develop and Compose; move them off it. Done when: the list is in the report and
  each move is made, with tests where the logic is pure.
  Done 2026-10-26: the list is in the run's report. Moved off the main thread: CameraController close, cameras() and open() (camera
  close and spec reads now run on IO or the camera thread; open is mutex-guarded and a stale open closes what it made); Develop's .cube
  import and export and preset save and delete (IO, preset write NonCancellable); Library thumbnails (AssetImage keeps grid-sized decodes
  in a bounded cache, three decoder threads); the Library A/B flicker keeps both pictures loaded; the Library and Lineage lists are
  computed once per change; the .cube read in Compose (LayerPixels.loadCubes). Tests: LineageTest, AssetDecodeTest, AwaitOpenedTest.
  Suite 1129 tests, 0 failures at merge. Left: "Save all now" still writes eight JSON files on the main thread; preview settings are
  still sent from the main thread; the freeze at 23:27 is not reproduced, so the cause is a guess (the camera close is the likeliest).
- [x] **S03 Draw stroke rendering cache** · status: done · size: M · depends: none
  Large stroke-heavy canvases redraw every frame. Cache finished strokes in a bitmap layer, redraw only what changed. Tests for
  the invalidation logic.
  Done 2026-10-25: LayerCachePlanner (pure) + LayerCache; 15 planner tests; suite 444 tests, 0 failures. Left: the reviewer's
  small points (density in the cache key, two more planner tests, a debug-readout comment, a small key class).
- [x] **S04 Node Lab: Touch pad, Smooth and Switch nodes** · status: done · size: S · depends: none
  Done 2026-10-25: Touch Pad (X, Y, Down; a pad under the toolbar), Smooth (time-based one-pole, Rise and Fall), Switch (Pick and
  Fade); Patch bay "Open as graph" makes a Touch Pad node. Pure maths and graph tests; suite 484 tests, 0 failures. Left: the
  reviewer could not run shell (no diff review of the wiring); Down works only in the top graph, not inside groups.
- [x] **S05 Node groups: remove or reorder a group socket; group presets** · status: done · size: M · depends: none
  Done 2026-10-25: Sockets… dialog (↑, ↓, Remove) inside a group, links and fields follow every Group node of that type; group
  presets (Save as preset, a Groups section in Presets, fresh ids). Suite 529 tests, 0 failures at merge. Left: sockets are edited
  only inside the group; no renaming; group presets are not shared to other phones.
- [x] **S06 Compose layer stack as a Node Lab node** · status: done · size: M · depends: none
  Done 2026-10-25: Compose Stack node (Colour section) with four image inputs that feed Compose's live layers (keeps centre,
  rotation, width); Unlinked: Rack or Hide; 48 MB node cache. Pure ComposeStack plan (16 tests). Suite 569 tests, 0 failures at
  merge. Left: single layers' opacity, blend or visibility are not inputs; the preview refreshes only on return to Node Lab.
- [x] **S07a Rack: Sequence and Loop projector inputs, Draw image-layer input** · status: done · size: M · depends: none
  Split from S07 (2026-10-25 run 0851): the first half of the rack spec in docs/ARCHITECTURE.md §13. Done: Sequence "Projector" input (full screen, live), Loop "Projector" input (first frame, filed in the Library), Draw "Image layer" input (a live layer, filed on Save; loops refused). Pure RouteLayers and LoopFrames (15 tests). Suite 720 tests, 0 failures at merge. Left: S07b; an unplugged live Draw layer stays as a grey box until deleted; the Sequence projector is not in the GIF or MP4 export.
- [x] **S07b Rack: Patch signals as jacks, rack presets** · status: done · size: M · depends: S07a
  Split from S07 (2026-10-25 run 0851): the second half of the rack spec. Done: the Patch bay is a rack unit with LFO 1, LFO 2, Touch X, Touch Y and Route 1 to 4 jacks; rack presets bar (six built-in, saved ones in rack-presets.json, one undo step). Pure RackPresets and PatchSignals (22 tests). Suite 794 tests, 0 failures at merge. Left: LFO jacks run on their own clock, not the patch's; route jacks follow list order, so deleting a route shifts them; presets are per project.
- [x] **S08 Plugins: node-graph behaviour behind a plugin's controls; the RGB parade as a real scope plugin; share a plugin file through Android's share sheet** · status: done · size: L · depends: none
  Done 2026-10-26: PluginBehaviour (controls feed a small graph of Node Lab number nodes to named outputs; cleaned on read to step types
  only; behaviours pause on ON_STOP); the RGB parade as a Scope plugin (Plugins rack unit, computed off the main thread); "Share…" through
  Android's share sheet. Suite 881 tests, 0 failures at merge. Left: behaviours are a list editor only (no Node Lab canvas); outputs are
  not Rack jacks; receiving a shared file still means Install; built-in plugins have no Share button.
- [x] **S09 MIDI in and out** · status: done · size: M · depends: none
  Done 2026-10-25: "MIDI in" unit (CC or note velocity on A to D; Gate; channel filter) and "MIDI out" unit (A to D as CC, sent on
  change); pure MidiParser, MidiMap, MidiOutPlan, MidiDevices; MidiBus closes while the studio is in the background. Two reviewer
  blockers fixed (a reopen loop on an unplugged device; a cancelled receive that could lose a message). Suite 685 tests, 0 failures at merge.
  Left: mappings reset on restart; Bluetooth MIDI needs a scan that is not built; first port of each device only.
  android.media.midi: a "MIDI in" rack unit (CC and note velocity to value jacks A to D, note on/off to a gate) and a "MIDI out"
  unit (jacks to CC). Pure mapping logic unit-tested; devices listed in the unit's back.
- [x] **S10 Sensor threshold as an envelope trigger** · status: done · size: S · depends: none
  Done 2026-10-25: ThresholdGate (pure, 14 tests), "Sensor" trigger in the envelope dialog (Roll, Pitch, Heading, Light, Mic,
  Shake; level, band, rises or falls). Left: the sensors and mic switch off in the background (a stopgap that S01 replaces);
  the dialog does not show the gate state; the mic permission is not asked for.
- [x] **S11 Layout leftovers** · status: done · size: M · depends: none
  Sequence as a slit-scan source; a separate UI Editor layout per orientation; Knob exact-value dialogs inside the panel; bounded
  layouts for Library, Node Lab and Studio in landscape.
  Done 2026-10-25: Slit-scan "Sequence" frames (One per item or By timing, capped at 32 items and 240 frames); UI Editor landscape
  front (older layouts load; "Like portrait" resets); Knob boxes inside the back's panel (ControlsPanel); PageSplit side panels for
  Library, Node Lab and Studio in landscape. Pure SequenceSlits and UiOrientation tests. Suite 772 tests, 0 failures at merge.
  Left: node number boxes still open a full dialog; toolbar rows in landscape scroll sideways; UI Editor zoom and pan are shared.
- [x] **S12 Themes reach canvas-like areas (node editor, rack panels)** · status: done · size: S · depends: none
  Done 2026-10-25: CanvasTheme (pure, 12 tests) feeds the node editor, Touch Pad strip, rack and studio back; Studio (default)
  keeps its colours (cable plug centre #0B0B0C). Suite 496 tests at merge, 0 failures. Left: the UI Editor's back; the
  reviewer's readability bar is loose (a linear luminance check, not a contrast ratio).
- [x] **S13 Draw high-res render mode (render at a chosen size, not only screen-exact)** · status: done · size: M · depends: S03
  Done 2026-10-25: Size button next to Save (Screen, 2×, 3×, 4×, 4096 px, 6000 px), saved with the canvas; pure RenderSize plan
  holds to 24 million pixels (23 tests). Suite 569 tests, 0 failures at merge. Left: the rest of the studio ignores the size;
  image layers gain no detail when scaled up; reviewer points: an unrecycled Subtract bitmap, and a clamped 2× can fall below 1×.
- [x] **S14 Animate barrier print export at a chosen DPI and paper size** · status: done · size: S · depends: none
  Done 2026-10-25: Paper (Screen, A4, A5, Letter) and DPI (150, 300, 600) chips in Animate's barrier view; PrintSize (pure, 17
  tests) fits the dpi to a 24-million-pixel limit. Suite 513 tests at merge, 0 failures. Left: the recipe keeps no paper or
  dpi; not yet printed; memory at the cap is unverified on a phone.
- [x] **S15 Compose: per-layer full Develop, transform handles on the canvas, text fonts** · status: done · size: L · depends: none
  Done 2026-10-26: a layer's Adjust has the Develop tabs (Light, Color, Mixer, Curve, LUT; .cube by layer amount, live view, thumbnails and
  Save agree); Move shows corner, edge and rotate handles, one undo step per drag (pure TransformHandles); text has 9 font families,
  italic, alignment and letter spacing (older text opens in Sans). Suite 848 tests, 0 failures at merge. Left: imported .ttf fonts;
  the .cube file is read on first use in the render path (ComposeRender.kt), to move off the main thread under S02; the healing/clone
  brush is S15b.
- [x] **S15b Compose: healing and clone brush** · status: done · size: M · depends: S15
  Done 2026-10-26 (retry): pure CloneBrush (plan, applyClone, applyHeal on IntArray with premultiplied alpha; snapshot isolation
  so heal does not drift; 15 tests). Clone and Heal tools on paint layers only; the source crosshair clears on layer change.
  CloneStroke and CloneMode in the model. Suite 1159 tests, 0 failures at merge. Left: photo/asset layers cannot be cloned
  (only paint layers); the heal is colour-shift only (no Poisson blending); the offset is locked for the whole session;
  non-paint layers selected via undo or add can still have Clone active briefly.
- [x] **S16 Feedback extras: a "never used" list in the digest; hand-made catalog entries for loose tests (tools/usecases/REVIEW.md)** · status: done · size: S · depends: none
  Done 2026-10-25: server.py digest gets a "Never used" part (rooms never opened, buttons never tapped, sliders never moved, 7 days);
  overrides.json has 17 hand-checked entries; CatalogSyncTest (2). Suite 529 tests, 0 failures; Python 24 tests OK. Left: about 60
  loose tests remain; the sliders list also covers rooms nobody opened (no scope-to-room map).
- [x] **S17 Phone-to-phone sync: send an asset or a whole project over the Loop link** · status: done · size: L · depends: none
  Done 2026-10-26: one Library picture (Send to, Other phone; keeps id, recipe and tags; not filed twice) or the open project (Loop
  room, Send, This project; saved as a new project, "-2" if the name is taken). 256 KB pieces with checksum; a bad file is discarded;
  a cut-off send resumes; a send stops on ON_STOP. Zip import capped (10,000 entries, 8 GB, 2 GB per entry). Suite 917 tests, 0
  failures at merge. Tested over loopback only, not between two phones. Left: no Save all before packing a project; one picture at
  a time; no progress in the Library.
- [x] **S18 Bridge: watch folders while the app is open (no background service)** · status: done · size: S · depends: S01
  Done 2026-10-26: FileObserver on DCIM/Camera, Pictures, Pictures/Screenshots, DCIM/Screenshots and Pictures/FV5; a RunGate
  hook starts and stops them with the app. Pure BridgeWatcher (paths, extension and event filter) with 12 tests; the flow merges
  into the existing BridgeEffects debounce+scan. Suite 1142 tests, 0 failures at merge. Left: new sub-folders created while
  the app is open are not picked up until the next resume; the Pictures watcher is not recursive.
- [ ] **S19 Room internals as default graphs (open a room's own routing as a graph)** · status: active · size: L · depends: S06
- [x] **S20 Review the merges that went in without a reviewer verdict** · status: done · size: S · depends: none
  The first runs' reviewers had no shell; S04's graph wiring was merged on a green build without a verdict (see SPRINT-REPORT).
  Run the sprint-reviewer on each such merge (`git show <merge>`), fix what it finds.
  Done 2026-10-26: only S04 (5156f18) lacked a verdict. Reviewer verdict APPROVE, no blockers; four minor points logged in PENDING
  (a per-frame list in the Switch node, the Touch Pad's Down inside a group, mute on the Touch Pad, a pointer-move map). No code
  changed. Test count unchanged at 848.

- [ ] **S21 Shake to report** · status: todo · size: S · depends: none
  A hard shake opens a short report sheet over the current room: what went wrong (typed), the room, the last debug lines and a
  screenshot of the room (only if the person ticks it). Sent as a feedback note through ReportBuilder; works whatever the feedback
  level is, because the person presses Send. Shake threshold in MathParams; can be switched off in Config.

## Phase 2: roadmap (the parts that need no decision from you)

- [ ] **R01 Vocabulary layer** · status: todo · size: L · depends: none
  Keyed strings with the Moiréic default; room titles, purposes and the most common labels moved first; theme files as overrides;
  Config shows only Moiréic until the names are decided (D3).
- [x] **R02 Calculator room** (expressions, history, variables, units, hex/bin/oct, result to a control or jack) · status: done · size: M · depends: none
  Done 2026-10-25: Calculator room (expressions with units and bases, history of 100, variables, ans, a result sent to a knob or a
  Result jack); pure Calc, Units, Bases, CalcBook (73 tests). Suite 685 tests, 0 failures at merge. Left: history and variables lost on restart; knobs only; no area or
  volume units. Reviewer non-blockers: deeply nested input can overflow the stack (add a depth limit); results above about
  3.4e38 become infinity on the jack; `5 km % 2` works in metres.
- [x] **R03 Math room** (browse the MathParams functions, plot them, apply to the current image) · status: done · size: M · depends: none
  Done 2026-10-25: Math room (six curve functions, a plot against y = x, sliders with Reset; applied as a tone curve to a Library image: RGB or one channel, Mix, Before; saved as a child with `math.curve`). Pure MathFunctions and MathPlot (20 tests). Suite 705 tests, 0 failures at merge. Left: the saved image has no recipe, so its settings do not reopen; images only from the Library, not Draw or a rack input; ARCHITECTURE.md not yet updated for the math module.
- [x] **R04 FM/AM module** (carrier and modulator, AM depth, FM index, ring mod; outputs as values and as image modulation; no audio until D2) · status: done · size: M · depends: none
  Done 2026-10-25: FM/AM node (Time section; AM, FM or Ring; Value, Carrier, Modulator outputs) and FM/AM Image node (Filter section; bands or line shift). Pure FmAm (20 tests) and node tests (8). Suite 748 tests, 0 failures at merge. Left: no rack unit (Node Lab has no number outputs to the rack); no FM/AM curve in the Math room; no sound (D2).
- [x] **R05 2D physics room with dyn4j** (bodies from Draw shapes, gravity from the accelerometer, collisions as triggers, frames to Animate) · status: done · size: L · depends: S01
  Done 2026-10-26: Physics room (dyn4j 5.0.2): Take from Draw; bodies Ball, Box, Line or Off by shape, chips cycle Moving, Fixed and Off;
  gravity from the accelerometer (SensorHub "physics") with a fixed fallback; hits on a "physics" rack unit (Hit trigger, Strength, Hits);
  To Animate, live recording (600 frames cap) and bake (600 frames cap). Pure ShapeMapper, Gravity, Triggers, PhysicsFrames; step loop
  paused by the run gate. Suite 1129 tests, 0 failures at merge. Left: room not saved with the project; changing bounce, friction, rate
  or walls restarts the world; no touch on the canvas; lines have no thickness; per-body hit jacks not built; frame rate not passed to Animate.
- [x] **R06 Low-poly vector 3D room** (primitives, orbit camera, flat shading, gratings on faces, SVG/image out) · status: done · size: L · depends: none
  Done 2026-10-26: 3D room (cube, low-poly ball, plane, cylinder, cone; one light; a grating per face; faces under 100 % opacity show
  moiré); orbit by drag, pinch zoom; picture to the Library, SVG to Download/Studio or share. Pure Geometry, OrbitCamera, Scene and
  SvgWriter tests. Suite 993 tests, 0 failures at merge. Left: no save of the scene across restarts; no undo; no routed inputs;
  faces behind the camera are skipped, not clipped; depth order is per face. Reviewer points: cache the scaled meshes per frame;
  the SVG save button could go if Library-only is wanted.
- [ ] **R07 Forge GUI builder and Wrap** (widget palette, bind widgets to ports, wrap as a .studiomodule) · status: todo · size: L · depends: S08
- [x] **R08 Lab notebook: notes and "rerun this experiment"** · status: done · size: M · depends: none
  Done 2026-10-25: notes on each notebook entry (saved in lineage.json, 2000 characters at most) and "Rerun" for Draw, Compose, Develop and Glitch saves (a new linked entry, run off the main thread). Pure Notebook (19 tests) and 3 ProjectStore tests. Suite 816 tests, 0 failures at merge. Left: Rerun is missing for FFT, Animate, Sequence, Node Lab, Math and captures; a Compose rerun with a live rack layer uses the current input; notes do not show in the Library.
- [x] **R09 Glitch pixel-sort and orientation field; colour transfer between two Library images** · status: done · size: M · depends: none
  Done 2026-10-26 (retry, run 0451): the feature is restored (59665fd reverted), `compose/Filters.kt` passes `checkCancel` by name, and both
  flavours compile. "Sort angle" switches Pixel sort to Angle; the first colour render waits for the colour image; the rack loads it once
  per pick (`LastLoaded`); a Notebook rerun keeps its colours. Suite 1046 tests, 0 failures at merge. Left: a Glitch filter on a Compose
  layer has no colour image; turning "Sort angle" switches the sort on even when a modulator moves it (to confirm).
  Merged, then reverted (59665fd) 2026-10-25: `:app:compileStudioDebugKotlin` failed.
  `renderGlitch` gained a colour-image parameter, but `compose/Filters.kt:59` still passes `checkCancel` in the old position
  ("Argument type mismatch: actual type is 'Function0<Unit>', but 'IntArray?' was expected"). The reviewer read the diff and did
  not compile it. Also for the next pass: the "Sort angle" rack knob does nothing while Pixel sort is Off; the first colour
  render flashes uncoloured; the rack re-decodes the palette on every render.
- [ ] **R10 Scripting IDE (JavaScript and Python first, then C++/Pascal via WebAssembly, HTML preview pane)** · status: todo · size: L · depends: none
- [ ] **R11 Rosetta Code browser** · status: todo · size: M · depends: R10
- [ ] **R12 "Copy as script"** · status: todo · size: S · depends: R10
- [ ] **R13 Sound as a signal (DAW mode): image and sound signals as one signal type** · status: todo · size: L · depends: none

## Decisions only you can make (workers never guess these)

- D1 Scripting languages. Answer 2026-10-27: go. JavaScript and Python first; C++ and Pascal compiled to WebAssembly; HTML as the IDE preview pane.
- D2 Does the studio get sound (a DAW mode)? Answer 2026-10-27: yes; image signals and sound signals are one signal type.
- D3 "Afterpilot" stays reserved until the right module shows itself. Themes (for the whole studio, not one plugin): Moiréic (default), Gaussian, Bayesian, Fourieric, Euclidian, Pythagorean, Eulerian, Newtonic, non-Newtonic, non-Euclidian, more over time.
- D4 Answer 2026-10-27: keep the first-run question; only the owner reads reports; add shake-to-report (S21 below).
- D6 Merge `sprint` into `main`, and put the repo on GitHub? Answer 2026-10-27: yes, when the sprint is done.

## Questions from the workers

(The coordinator adds questions here when an item cannot go on without you.)

- (answered 2026-10-26) Shell commands were refused because the run's allow-list was too narrow. It now allows the usual
  read-only commands, git (not push, remote, reset --hard, clean or main), Gradle and the tools scripts. S08, S15, S17, S19, R01 and
  R06 are back to `todo`. R09 is still `needs-fix` (see its line).

## How the sprint runs

- `tools/agent/sprint.ps1`, run by the Windows task `MoireStudio-Sprint` every hour and at logon, starts a headless Claude coordinator
  (`tools/agent/SPRINT-PROMPT.md`) unless one is already running. The coordinator takes up to two items at a time, hands each to a
  `sprint-builder` agent in its own git worktree, has a `sprint-reviewer` agent check it, merges into `sprint`, runs the full tests,
  and updates the docs and the checklist page. Pure-logic pieces can go to the local model (`tools/agent/run-local.ps1`).
- When your Claude usage limit is reached, the run stops and the runner registers `MoireStudio-Sprint-Resume` for the reset time;
  the hourly task is the fallback. Run reports go to `docs/SPRINT-REPORT.md`; the Plan page shows this queue.
- Workers never push, never touch the Pi or the phones, never merge into `main`, and never set a checklist result.
