# Moiré Studio — architecture v3

**Core idea:** one app on both phones. A **hub** for the apps already in use (Samsung Gallery, Samsung Notes, Camera FV-5) and a **studio** with its own versions of each tool. Internal and external tools share one interface, so both land in the lineage. A patch bay routes phone sensors to any parameter — a modular synth for images.

## 1. Devices and roles

| | S21 (One UI) | S9+ (LineageOS 13) |
|---|---|---|
| Samsung Gallery, Notes, AI Style | Native | No (bundled tools replace them) |
| FV-5 | Yes | Yes |
| Display | Up to 120 Hz | 60 Hz |
| Cameras | Wide, ultrawide, 64 MP tele | Main with mechanical dual aperture (f/1.5 / f/2.4) |
| Natural role | Hub, Samsung bridge, projector or camera | Second camera, projector, sensor controller |

- Roles swap per session: Hub, Projector, Camera, Controller.
- The 120 Hz vs 60 Hz mismatch is a tool (more interlace bands), exposed as a setting.
- First-run check: does LineageOS expose the S9+ aperture and manual shutter via Camera2?

## 2. Layers
```
┌──────────────── Rooms (UI) ────────────────┐
│ Library · Draw · Animate · Develop · FFT    │
│ Glitch · Sequence · Camera · Loop · Bridge  │
├──────────────── Patch bay ─────────────────┤
│ sensors / LFOs / touch → any parameter      │
├──────────────── Tool registry ─────────────┤
│ internal tools  |  external tool adapters   │
├──────────────── Engine ────────────────────┤
│ GPU render (strokes, shaders) · CPU (FFT,   │
│ byte-level glitch) · encoders · Camera2     │
├──────────────── Store + sync ──────────────┤
│ project store · lineage graph · watchers ·  │
│ device link (S21 ⇄ S9+)                     │
└─────────────────────────────────────────────┘
```

Shared tool interface:
```
Tool { id, inputs: [Asset], params, run() → [Asset], provenance }
```
External adapters: send file → open app → watch for returning file → ingest with provenance `external: <app>, <time>`.

## 3. Rooms

**Library** — generation tree (parents, operation, params, device, camera settings); re-run a branch with one change; tags, compare, A/B flicker compare.

**Draw (Notes replacement)**
- Vector stroke objects with saved transforms (move, uneven stretch, rotate).
- Stroke stacks: base line + dashed/textured overlay lines; per sub-stroke pen type, width, color, texture, dash length/gap/phase, offset.
- Lasso → handles → copy/paste/duplicate → change style per sub-stroke; live moiré at 60 fps.
- Generators: grating, sine beat pair, ellipse hatch, radial burst.
- Blend per object: Normal, Lighten, Multiply, Difference, Screen.
- Readouts: angle, scale, line spacing, predicted beat. Render modes: screen-exact / high-res.

**Animate**
- Moiré-shift stop motion: keyframes or capture-while-dragging; step helper; onion skin; hold counts.
- Barrier grid (scanimation): interleave N frames, slit grating overlay, print export.
- Record gyro/touch as motion tracks → keyframes.

**Develop (Gallery editor replacement)** — per-hue HSL mixer, curves, levels, exposure, temperature/tint; presets; `.cube` LUT import/export; lasso/brush/spot-color masks; style transfer 0–100 (Samsung Style via S21 bridge).

**FFT lab** — forward FFT (magnitude/phase, colormaps, log scale); paint/mask spectrum; inverse FFT; magnitude/phase swap between two images.

**Glitch lab** — byte-level stride/width/channel/chroma/plane-fill reinterpretation (raw bytes, not floats); re-encode generations; parameter sweep wall. After the byte stages come two pixel stages (`glitch/model/ColorTransfer.kt`, `PixelSort.kt`, pure Kotlin): colour transfer from a second Library image (mean and spread per channel, in Lab or RGB, with an amount), then pixel sort (runs within a key range sorted by brightness, hue or saturation, along lines that follow an orientation field: one angle, or the edges of the image from its smoothed structure tensor).

**Sequence + projector** — timeline, durations, loop, ping-pong; projector mode (full screen, max brightness, exact timing from decoded frames, black-frame insertion, refresh lock); GIF (palette dither kept) and MP4 export.

**Camera** — manual shutter/ISO/focus/WB/burst/RAW; S9+ aperture if exposed; software optical stack (additive = sum × gain + clip; interlace = rolling-shutter model); live grating/barrier overlay in preview; FV-5 adapter (launch + watch folder; no control API).

**Loop** — projector phone + camera phone; synced or free-run; captures auto-ingest as the next generation; optional torch strobe (low-rate, jittery).

**Bridge** — send to Samsung Gallery editor / Notes / FV-5 / any image app; share target; watch folders (DCIM, Screenshots, Pictures, FV-5) matched to outgoing sends by time window; screen recording (per-session consent).

## 4. Patch bay

| Source | Example destination |
|---|---|
| Gyro / rotation | Bundle angle, dash phase |
| Accelerometer | Object offset |
| Magnetometer | Hue rotation |
| Ambient light | Exposure, grade intensity |
| Microphone level | Line spacing, strobe rate |
| Proximity | Freeze / capture trigger |
| Touch position | X/Y pad on any two params |
| LFOs (sine, square, random) | Anything |

Range, curve, smoothing per route. Sensors from the other phone are routable. Recorded streams become animation tracks.

## 5. Data model
```
Project
 ├─ Assets       images, video, captures, found images, external returns
 ├─ Canvases     stroke objects → stroke stacks → sub-strokes
 ├─ Animations   keyframes | sensor tracks | barrier-grid sets
 ├─ Sequences    ordered frames + durations
 ├─ Patches      sensor → parameter mappings
 ├─ Recipes      operation graph per output (JSON)
 └─ Lineage      asset → parents + recipe + device + external step
```
Originals never modified. Exports to `Pictures/Studio/`.

## 6. Sync
Files: Syncthing over the tailnet (or own transfer). Live control: socket between the two apps (triggers, sensor streams, role commands). S21 holds the master project.

## 7. Stack
Kotlin + Compose, one APK, minSdk 33 · OpenGL ES 3 / Vulkan · AGSL/GLSL · Camera2 · MediaCodec · GIF encoder · FFT (CPU or GPU compute) · TensorFlow Lite · MediaStore observers · MediaProjection.

## 8. Platform limits
- Samsung Notes strokes can't be imported as vectors (proprietary format) — Notes output arrives as images.
- FV-5 can't be remote-controlled — launch + watch folder only.
- No background camera — Camera role needs the app in the foreground.
- Screen recording asks consent every session.
- Samsung AI Style only on the S21.
- Android 14+ "selected photos" access breaks watch folders — needs full media access.

## 9. Build order
1. Draw + Library/lineage
2. Animate
3. Sequence + projector
4. Bridge + watch folders
5. Camera + Loop
6. Patch bay
7. Develop
8. FFT lab, Glitch lab
9. Compose (main layered editor): stage 1 layers, blend, masks, adjust; stage 2 blending options and basic
   editing tools (selections, crop, paint, fill, gradient, text, filters incl. FFT/Glitch per layer)
10. routing-nodes (stage 3, see §12)

## 10. Formulas

| Effect | Formula |
|---|---|
| Parallel beat spacing | Λ = p1·p2 / \|p1 − p2\| |
| Rotated beat spacing | Λ = p / (2·sin(θ/2)) |
| Dash beat along lines | d1·d2 / \|d1 − d2\| |
| Shift amplification, parallel | fringe moves Δ · p1 / \|p1 − p2\| |
| Shift amplification, rotated | Δ / (2·sin(θ/2)), ~90° to the drag |
| Interlace | row r sees frame floor((t0 + r·t_row) / T_frame) |
| Barrier grid | slit spacing = N × strip width |
| Animation aliasing limit | shift per frame < p/2 |

## 11. Risks
- Scope: keep a usable app after step 4 before starting later rooms.
- Sterile simulation: physical loop stays the reference.
- Lineage gaps: time-window matching can mismatch; confirm prompt when uncertain.
- Screen-exact vs high-res drift: lock one render mode per piece.

## 12. Node Lab, formerly routing-nodes (stage 3)

A node-based studio routing editor: the internal routing of every module, and custom studio
pipelines, as a node graph. The UI follows Blender's node editors (Shader / Geometry / Compositor),
made touch-first.

**Why nodes.** A DAW-style routing page or a Eurorack/Reason-style patch rack both fall short: the
studio's pipelines branch, merge and loop (camera → develop → compose → projector → camera), and
each module has many parameters worth wiring. A node graph shows all of it and scales.

**Graph model.** `Graph { nodes, links, frames, presets }`. A node has typed input and output
sockets; a link joins an output to an input of a compatible type. Socket types, colour-coded as in
Blender:

| Type | Colour | Carries |
|---|---|---|
| Image | yellow | a bitmap / asset reference |
| Canvas | orange | a Draw recipe (vector layers) |
| Frames | green | an ordered set of images or canvases (Animate, Sequence) |
| Value | grey | a number (any slider in the studio) |
| Signal | violet | a time-varying value (sensors, LFOs: today's Patch bay) |
| Trigger | white | an event (capture, shutter, frame tick, loop step) |

**Nodes.** Every room becomes one or more nodes, so its internals are routable:
- Sources: Library asset, Camera capture, Bridge inbox, Draw canvas, Generator (grating, burst, ellipses).
- Processors: Develop (and its parts: exposure, curve, mixer, LUT), FFT filter, Glitch, Compose layer stack,
  Blur/Sharpen/Noise, Mix (blend modes), Mask, Transform, Crop.
- Time: Animate (frames), Sequence (timeline), LFO, Sensor, Clock, Step counter.
- Sinks: Library save, Projector, Export GIF/MP4, Send to app (Bridge), Loop link (other phone).
- Utility: Reroute, Group (node groups with exposed sockets), Switch, Math (add, multiply, map range),
  Value, Frame (labelled box).

**Editor (Blender workflow, touch).**
- Canvas: pan with one finger on empty space, pinch to zoom, double-tap to frame all.
- Add menu: a searchable list of nodes by category (Blender's Shift+A); long-press on empty canvas opens it.
- Links: drag from a socket to a socket; drag from an input to empty space to cut; a "knife" two-finger
  swipe cuts every link it crosses (Ctrl+right-drag); drag a link onto a node's body to insert it inline.
- Select: tap; box-select by dragging from empty space in Select mode; select all/none buttons.
- Nodes: collapse/expand, mute (pass-through, M), duplicate (Shift+D), group / ungroup (Ctrl+G / Tab
  to enter a group), frame selection (Ctrl+J), reroute dots on links.
- Each node shows its parameters as the studio's exact-value sliders; an unlinked Value input shows its
  own field, a linked one shows the link (as in Blender).
- Preview: a viewer node shows any image socket on a backdrop (Compositor's Viewer), tap a node to
  preview its output.

**Node Wrangler tools (Blender add-on, ported).**
- Lazy connect: drag between two nodes to connect their best-matching sockets.
- Lazy mix: drag between two image nodes to insert a Mix node.
- Preview any node into the viewer with one tap.
- Swap links between two inputs; add Reroutes on all outputs; align nodes; quick Math / Mix on a link.
- Batch-change a parameter (e.g. blend mode) on all selected nodes.

**Presets.** A graph (or a node group) is saved as a named preset in the project and in a shared
library across projects: "Moiré over photo → Develop → Projector", "Camera loop with FFT feedback",
"Patch: gyro → grating angle". Each room's built-in behaviour is itself a default graph, so editing a
room's internal routing means opening its graph.

**Running.** The graph evaluates lazily (only what a sink needs), caches node outputs by inputs,
and re-runs on change or on triggers. Every image a sink saves is filed in the Library with the
graph (and preset name) as its recipe, so lineage covers whole pipelines.

**Build order inside stage 3.** (1) graph model + evaluator + Value/Image nodes, (2) the editor canvas
with links and the add menu, (3) room nodes for Library, Develop, Compose, Generators, Save,
(4) time nodes (Animate, Sequence, LFO, Sensor, Trigger) replacing the Patch bay's routes,
(5) Node Wrangler tools, (6) presets and node groups, (7) two-phone nodes (Loop link, remote sensors).

## 13. Studio routing: the studio's back (stage 4)

Node Lab (§12) builds image pipelines inside one room. Studio routing is different: it connects the
rooms themselves, live, so a change in one module shows up at once wherever it is patched.

**The back side.** Node Lab's **Studio routing** turns the whole app round (as Reason's Tab turns its
rack). The back is the front mirrored in the y axis: the room bar runs right to left, and each module
tab shows that module's back where its front is. The upper panel (the canvas on the front) carries the
module's jacks: outputs say where their cables go, inputs what feeds them, and a tap patches either.
The lower panel (the controls on the front) carries the module's knobs and buttons for its main
settings, and its **module presets**: the module's whole state saved by name and recalled undoably.
**All cables** shows every module's back at once, with the cables hanging between the jacks, coloured
by type; drag from an output to an input there, or drag a plugged input to move or pull its cable.
**Flip to front** returns to the front of the module being looked at.

**Jacks.** Types: Canvas (a Draw recipe, orange) and Image (pixels, yellow); an Image input accepts a
Canvas (rendered on paper). An input takes one cable; an output feeds any number. A cable that would
feed a module from itself, directly or round a loop of modules, is refused.

| Module | Outputs | Inputs |
|---|---|---|
| Draw | Canvas | Image layer (a live image layer) |
| Animate | Current frame (canvas) | |
| Sequence | | Projector (shown full screen, live, instead of the timeline) |
| Compose | Composite | Layer 1–4 (each becomes a live layer) |
| Develop / FFT / Glitch | Result | Source (replaces the picked image) |
| Camera | Last capture | Overlay (shown over the preview) |
| Loop link | From other phone | Projector (the first frame; takes the Sequence's place) |
| Node Lab | Viewer | In 1, In 2 (read by Module In nodes) |
| Patch bay | LFO 1, LFO 2, Touch X, Touch Y, Route 1–4 (values) | |

**Running.** Outputs are produced only while something listens (a cable, or the Rack's front), in
the background, debounced; inputs re-render when what arrives changes. Saving a result made from a
live input files that input in the Library first ("route.snapshot"), so lineage still has a parent.
The patching is saved with the project (routing.json) and is undoable.

**Projector and image-layer inputs (2026-10-25).** Draw's Image layer input adds one image layer whose
asset id is `route:draw.in` (`RouteLayers`); `LayerImages` hands it the cable's pixels, and Save files
the picture in the Library first and saves the canvas with that file instead. The Sequence unit's
Projector input makes the Projector button show the patched picture, live, at the Sequence's rate and
brightness. The Loop unit's Projector input is filed in the Library (once per new picture) and is the
loop's first frame; with "Next frame" not on feedback it is shown each generation in place of the
Sequence (`LoopFrames`). Library → Use in Loop unplugs it.

**Patch signals and rack presets (2026-10-25).** The Patch bay is a rack unit (`PatchSignals`). Its LFO
and touch jacks need no hardware: while a cable listens, `PatchSignalBus` sends them each frame (the
LFOs with the same seeds as the patch's routes), and it idles in the background. Route 1 to 4 are the
patch's first four routes in list order; while the patch is live, `PatchEngine` sends where each
route's output sits in its range (0 at min, 1 at max). **Rack presets** sit above All cables: a whole
cable layout saved by name (rack-presets.json, with the project) next to built-in ones that cannot be
deleted (`RackPresets`). Loading one replaces every cable as one undo step; its cables are patched
again one by one with the usual rules, so one whose jack has gone or would loop is left out.

**Later.** The two phones' racks seen as one.

## 14. UI Editor (stage 5, 2026-10-21)

One room, `uieditor/`, with the whole UI on one big pan and zoom canvas.

- **Registry.** Every module's controls come from the same `ModuleBackSpec` list the back panels use (`knobs` are
  sliders, `buttons` are toggles when they have an on/off state, otherwise buttons). `UiModel.seed` lays the modules
  out as panels in rows of four; `UiModel.merge` keeps what was saved and adds what is new, because some modules
  only offer some controls in some states (Draw's knobs follow the selected layer).
- **Front** is the layout editor: drag a control or a panel header (snapped to a 4 unit grid), drag a control's corner to
  resize, pinch and drag the empty canvas to zoom and pan. Rooms still draw themselves: the layout is stored, not yet
  applied to the real screens.
- **Back** is the same canvas turned over: every x is mirrored across the canvas width (`UiModel.mirrorX`), and each
  control becomes a jack instead of a slider. Drag from any jack to any other to patch a cable; a cable has no direction
  rule yet (not to itself, not twice). Cables are stored and validated, they do not drive anything yet.
- **Saved** with the project as `ui-layout.json` (`ProjectStore.saveUiLayout`), undoable (`Undoable<UiLayout>`).
- Switched-on plugins add their own panels (§21).

## 15. User files, math parameters (2026-10-21)

- `user.profile` (app files folder): name, handle, about, theme, interface size, start room, every flag and the developer
  options (debug level, terminal, "copy as script", A/B two-finger hold, palette), the enabled plugins, and the math
  parameter overrides. `user.workspace` (the app's external files folder, reachable over USB): the open project and the
  last room. Project files stay in `projects/<name>/`. `UserConfigStore` reads and writes them (written whole to a temporary
  file and moved into place); on first start it migrates the old SharedPreferences. `StudioPrefs` is now a facade over it.
- `math/MathParams` is the registry of tunable numbers (key, default, range, unit, description). Overrides are live and saved
  in `user.profile`. So far the slider thumb's timings and step sizes use it; the rest of the maths still has its numbers
  inside the functions. The formulas live in `math/` (`Sliders`, `Fringe`, `Envelope`, `FmAm`), `channels/` (`ColorConv`) and
  `sensors/`, each pure and tested.
- `math/FmAm` (R04) is a carrier and a modulator sine with AM depth, FM index (phase bent by the modulator) and ring
  modulation, all -1..1. Node Lab uses it twice: the FM/AM node (Time) gives the wave, the carrier and the modulator as numbers
  between Min and Max; the FM/AM Image node (Filter) lays the wave over a picture one value per row or column (`FmAmImage`:
  darken by Amount, or slide each line by up to Shift px). There is no sound until D2 is decided.

## 16. Debug terminal (2026-10-21)

`debug/Debug` is a facade with three levels (1 errors and warnings, 2 info and events, 3 trace; 0 keeps nothing) over a ring
buffer (`DebugLog`, 2000 lines). Logcat still gets everything. `DebugTerminal` shows the buffer over any room (Studio →
Config → Developer): standard out only, no input field, Clear and Close. The old `Log.i/w` calls all go through it.

## 17. Colour spaces and channels (2026-10-21)

`channels/ColorConv` converts between RGBA, CMYK (the plain formula, no printer profile) and Lab (D65). It is used by
Develop's channel mixer (gain and offset per channel, baked into the same 3D table as the other adjustments, so it costs
nothing at run time) and by the 3D analysis tool (any channel of any space as the height). Not built: the Split and Merge
nodes, channel jacks on the rack, per-element channel setups.

## 18. Camera overlay burn-in (2026-10-21)

The overlay is drawn by one function, `drawOverlay`, for the live preview and for the capture. At the shutter,
`overlaySnapshot` freezes the settings (so changes during a burst do not leak in); `develop()` composites the overlay onto the
stacked image with `compositeOverlay` (blend mode, opacity, scaled from the preview box to the photo). The preview itself
stays plain alpha, because blending over a `SurfaceView` is unreliable. The overlay settings are recorded in `CaptureInfo` and
restored by "Open in Camera"; a Library image used as overlay becomes a parent of the capture. Loop captures are not burnt.

## 19. Draw groups (2026-10-21)

`LayerGroup` (id, name, parent, collapsed, visible) is saved in `CanvasDoc.groups` (older recipes load with a group entry
made for each old group number). The logic is in `draw/groups/GroupLogic` (effective visibility up the parents, descendants,
duplicate with id remapping). The layer tabs are rows that branch downwards: an open group adds a row of its own below. Hidden
groups are left out of drawing, hit testing and everything that renders or publishes the canvas (`CanvasDoc.flattened`).
Duplicate copies in place (no offset) and just above the originals; Paste keeps its offset.

## 20. A/B compare, gestures, history (2026-10-21)

- **A/B** (`abcompare/`): hold a canvas still (500 ms, under 8 dp of movement; one finger, or two as set in Config). The
  quarter held picks the view: top left the module's input, top right a vertical split (drag to move it), bottom left a
  horizontal split, bottom right B in full. A is what the room shows (or a cable into the A jack), B is a cable into the
  B jack or what "Snap B" kept. In Draw and Develop only so far. The Rack has an "A/B compare" unit with those two jacks.
- **Gestures** (`ui/gestures/`): the classifier is pure; `studioGestures` watches every touch without taking it. Two-finger
  tap undo, three-finger tap redo, three-finger swipe changes room, a swipe in from the right edge opens History, a
  four-finger tap lists them.
- **History**: `Undoable` now keeps a labelled timeline on `ui/history/History`; `DrawState` keeps its own with labels. The
  panel lists the steps newest first and jumps to any. Animate, Sequence and Develop keep their older undo (no list yet).

## 21. Plugins and the Module Forge (2026-10-21)

A plugin is a `manifest.json` (id, name, version, author, kind, widgets) in a `.studiomodule` zip and in `filesDir/plugins/`.
Data only: behaviour will come from node graphs, never code. Studio → Plugins installs (entries that could leave the folder
are skipped), switches on and off, exports and removes; the Module Forge room builds one. A switched-on plugin gets a panel in
the UI Editor. Not built: the generic runtime that draws a plugin as a room, sharing over Bridge or Loop.

**Behaviour (2026-10-26).** `manifest.behaviour` is a Node Lab `Graph` shaped like a group body (`plugins/PluginBehaviour`):
Group Input (node 1) has one socket per number control (slider = Value, toggle = Gate, button = Trigger), Group Output (node 2)
holds the plugin's outputs. Steps are Node Lab's number nodes only (`STEP_TYPES`, at most 48); `sync` re-fits the graph to the
controls and cleans it on every decode, so an imported file cannot bring in picture, sensor or output nodes. `BehaviourRun`
evaluates it with the normal `Evaluator`; `PluginState.runBehaviours` steps the switched-on ones once a frame (paused on ON_STOP).
Outputs show in the Plugins room and on the plugin's module back as "(out)" knobs that cables can read. The Forge edits the
graph as a list (steps, each input fed by a control, a step or its own number). **Scopes:** `manifest.scope` names one of the
studio's own scopes (`PluginScopes`: RGB parade, brightness waveform); the Plugins room draws it from the Rack's Plugins unit,
jack `plugins.in`. **Share:** `PluginStore.shareFile` writes the zip to `cache/shared/` (FileProvider) and opens the share sheet.

## 22. Slit-scan (2026-10-21)

`slitscan/SlitScan.scan` takes each slit (a strip of the picture) from a different frame, in order, plus a time offset per slit
(`slitOffsets`: none, ramp, wave, random from a seed). With no offsets and the slit at the strip width it is the barrier-grid
interleave Animate already makes. The room takes its frames from Animate; saving files the result in the Library.

## 23. Working with the local model (2026-10-21)

See [AGENT-WORKFLOW.md](AGENT-WORKFLOW.md): task cards in `tasks/`, a runner that has the local model write pure logic against
hand-written tests, and a review gate before anything is accepted.

## 24. Draw layer cache (2026-10-25)

The Draw canvas keeps finished layers as two bitmaps (`draw/LayerCache`): the full picture (every visible layer) and a base
picture (the layers below the one being edited). `LayerCachePlanner` is pure and decides each frame: draw the full picture if
no layer changed; paint only new top layers onto it; while a layer keeps changing (slider, move, stretch), draw the base and
the layers from the change up live; once a frame passes with no change, bake everything into the full picture again. A new
canvas size or a newly loaded image throws both away. A subtracting layer is only ever painted from a blank sheet up.

## 25. MIDI bus and the Calculator (2026-10-25)

`midi/` reads and writes MIDI through `android.media.midi`. `MidiParser` turns the byte stream into messages (running status,
real-time bytes, system exclusive skipped). `MidiMap` maps CC, note velocity and the gate to the MIDI in unit's jacks A to D
and Gate; `MidiOutPlan` builds the CC bytes for the MIDI out unit and sends only changes. `MidiDevices` picks the device to
open. `MidiBus` is the thin Android side: incoming bytes are queued on the MIDI thread and read on the main thread; a unit's
device is open only while the unit is on and the studio is in front. The manifest marks MIDI as optional.

`calc/` is the Calculator room. `Calc` (lexer, parser, evaluator, formatting), `Units` and `Bases` are pure; `CalcBook` keeps
the history (100 lines), variables and the knob fit. A result is published on the `calc` rack unit's Value jack through
`router.publishLevel`; it is not scaled to 0 to 1.

## 26. Lab notebook: notes and reruns (2026-10-25)

The Studio's Notebook tab lists the Library, newest first. An entry's note is `AssetRecord.note`, kept in `lineage.json`
(`ProjectStore.setNote`). Rerun makes an entry again from its recipe: `studio/Notebook` (pure) says which kinds can be rerun
(Draw, Compose, Develop and Glitch renders) and builds a `RerunPlan` (recipe, the first run's parents, size); `rerunExperiment`
renders it off the main thread and files a new entry with `rerunOf` set to the first run, so a rerun of a rerun still points at
the first. Animate, Sequence, Node Lab, FFT and captures are reopened in their room instead.

## 27. Nothing runs off screen (2026-10-26)

`foreground/RunGate` is one flag: `MainActivity` sets it on ON_START and clears it on ON_STOP (`Foreground.gate`). Loops call
`awaitStarted()` before each step, or `sleep(ms)` instead of `delay` (it does not end while off screen), or check `isStarted`
to close what they hold (OSC sockets, MIDI devices). Things that hold hardware add a `RunGate.Hook`: `SensorHub` lets every
sensor and the microphone go, `LoopEngine` stops a send and closes the link (and links again on return), Feedback makes its
checkpoint. No other code watches the lifecycle. A new loop, socket, sensor or timer must do one of these.
