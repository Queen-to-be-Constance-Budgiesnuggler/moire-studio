# Plan: final pre-pilot changes (Moiré Studio)

> **Status (2026-10-21):** the pre-pilot slice was built (see the changelog in studio-log.html and "Built 2026-10-21" in
> PENDING.md). What is not built is listed in PENDING.md under "Not built yet". This file is the original plan, kept as it was
> approved; it is the source for the roadmap items (scripting IDE, 3D, Rosetta Code, physics, FM/AM, and so on).

## Side task: local coding model on the PC (separate from the app work)
Found: RTX 3050 (8 GB VRAM), i5-12600K (10 cores), 31.7 GB RAM. LM Studio data is in `~/.lmstudio`, with `lms.exe` there. The daemon times out (stale version), and the downloaded models are Qwen2.5-Coder-7B and Qwen2.5-VL-7B. The user chose: update LM Studio by installer, replace the old model with Qwen3-Coder 30B-A3B (MoE).
Steps: (1) download the latest installer from lmstudio.ai and run it silently, keeping `~/.lmstudio`; (2) `lms get` the Qwen3-Coder 30B-A3B GGUF at about Q4, after checking the catalog name and size; (3) `lms server start`, load it with GPU offload and the MoE experts on CPU, flash attention, 16k context; (4) verify with a test request on `localhost:1234`; (5) only after it works, remove the old Qwen2.5-Coder-7B files (the user asked for old out, new in; keep the VL model); (6) delegate small, well-specified tasks via the lmstudio-coding-agent contract (`<<<AGENT>>>` prefix, HTTP calls, because no `llm_studio` tool is exposed here), and review all output before use. Save a memory about the setup.

## Vision / philosophy (user's words, to guide every decision)
- *"A complete dynamic software stack, always unified in a dynamic range from simple harmonic to hypercomplex distorted signal control station. One software to boil down every digital workflow into one unified frequency."*
- **The Blender principle:** Blender's boilerplated scripting interface is what let the user learn programming. Moiré Studio should do the same. Nearly everything should be scriptable, with ready-made boilerplate templates for every module, plugin, node, scope and math function, so a user can open one, change a line and learn by tinkering. This drives the scripting module, the math transparency work and the Forge.

## Context
No user tests have been run yet. These are the last features to land before the pilot checklist (docs/TESTING.md, docs/studio-log.html) is worked through. Paths are under `app/src/main/java/dk/cbavf/studio/` (shortened `…/`). Test only on the S9+ (see memory: test-phones-adb; check the foreground before driving UI).

Order is by risk: small and isolated first, UI Editor last. Suggested build order: 5d slider → 1 camera → 2 draw → 3 config → 4 debug terminal → 5b/5c channels → 5e sensors → slit-scan → A/B → palette → 5 UI Editor → 5f notes and 6 docs (docs are kept up to date after each step). This is a lot for one night on the S9+. Undo history goes right after 2 draw (it touches Draw's history). The gesture layer goes right after the slider (5d) and before A/B, which depends on it. The Module Forge skeleton comes right after the UI Editor. The 5e-ii items come after the UI Editor. If time runs out, the 5e-ii items, the A/B module, the palette and the UI Editor's back side are the natural cut lines, and they stay listed in PENDING.

## 1. Camera overlays burned into the capture, with blend
Cause: overlays are a Compose `OverlayCanvas` over a `SurfaceView` (`…/camera/CameraRoom.kt:181`). `CameraController.capture` (line 180) targets only the JPEG reader, and `develop()` (`…/camera/CameraState.kt:128`) never receives overlay data.

- `CameraState.kt`: add `overlayBlend: Blend = Blend.Normal` and `burnIn: Boolean = true` beside lines 50-56. Extend `info()` and `restore()` to store and read overlay, blend, opacity, spacing, angle and burn-in. Add the same fields to `CaptureInfo` (`camera/model/Capture.kt`). Update the "never burnt in" comments.
- New `…/camera/OverlayComposite.kt`:
  - `class OverlayRender(overlay, blend, opacity, spacing, angle, assetId, drawLayers, barrierStrip, barrierFrames, routed: Bitmap?)`, a snapshot.
  - `fun compositeOverlay(base: Bitmap, o: OverlayRender): Bitmap`.
  - Render the overlay at the base size with `CanvasDrawScope` + `drawLayers(sheet=false, images=LayerImages::blocking)`, as in `library/Render.kt:26` `renderBitmap`. Port the grating, barrier, image and routed fit-and-centre maths from `CameraRoom.kt:190-229`.
  - Blend with `Canvas(base).drawBitmap(ov, 0f, 0f, Paint{alpha; blendMode = blend.android})`, the pattern at `nodes/AndroidBackend.kt:98-112`. `Blend.android` already exists (`compose/ComposeRender.kt:345`).
- `develop()`: call `compositeOverlay` after upright and stack (line 132), before `compress` (line 135), when `burnIn`.
- `ui/StudioApp.kt:516` `capture()`: build the `OverlayRender` snapshot next to `info` before `camera.capture`, so state changes mid-burst don't leak in. Thread it through `saveCapture`. Do the same at `loop/LoopEngine.kt:371`.
- Record an Image/routed overlay asset id as a lineage parent (currently `emptyList()` at `CameraState.kt:169`).
- `OverlayControls` (`CameraRoom.kt:376`): add a Blend `ChipRow` copied from `DrawRoom.kt:330`, and a Burn-in toggle. The preview stays source-over, because blending over a `SurfaceView` is unreliable. Label it "blend applied at capture".

## 2. Draw: groups, collapse, show/hide, duplicate in place
Today `Layer.group: Int?` is flat (`…/draw/model/Layers.kt:207`), `visible` is per layer, and `paste()` nudges +32 (`DrawState.kt:235-244`, `nudge` 439).

- Model: add `@Serializable data class LayerGroup(id, name, parent: Int?, collapsed, visible)`. Add `groups: List<LayerGroup>` to `CanvasDoc` and `Snapshot`, with defaults so old projects load. Keep `Layer.group` as the owning group id. Make `groupSelection()` create a `LayerGroup`, and nesting is allowed.
- `DrawState`: add `toggleGroupVisible`, `toggleGroupCollapsed`, `showAll/hideAll`, `effectiveVisible(layer)` (own visible AND every ancestor visible), `moveLayerBy` made tree-aware, and group ids included in undo `checkpoint`.
- Use `effectiveVisible` in the renderer (`draw/Render.kt` `drawLayers`), `tapSelect` (421) and `lassoUp` (404).
- Duplicate in place: add `duplicate(inPlace = true)`, which pastes with no `nudge` and keeps the original's relative position. It must also remap group ids, including a new `LayerGroup` per copied group with the parent remap. Plain Paste keeps the offset. Note that `Grating` is already un-nudged.
- UI, `DrawRoom.kt` Controls (212-278): replace the flat third `ScrollRow` with a recursive tab renderer. A group is a header chip (name, count, eye, caret). Expanding it adds a new `ScrollRow` below holding its children, and a subgroup nests another row below that, so tabs branch downward. Add Show all / Hide all buttons. Reuse `HoldChip` (`ui/HoldChip.kt:35`) and `ScrollRow` (`ui/Widgets.kt:40`).

## 3. Studio config: user.profile, user.workspace, project files
Today `StudioPrefs` (`…/studio/StudioPrefs.kt`) wraps SharedPreferences "studio", with stray stores in `StudioApp.kt:137-150` (room) and `LoopEngine.kt:71` ("loop").

- New `…/studio/UserConfig.kt`: one `@Serializable Config` data model, a `JsonFileStore` built from `ProjectStore`'s `writeAtomic` and `readOrNull` (`library/ProjectStore.kt:280-304`, extract them to a shared helper), and Compose-state exposure.
  - `user.profile` in `filesDir`: name, handle, about, plus every studio-level config flag (theme, uiScale, startRoom, keepScreenOn, haptics, loop role/host defaults) and developer options (debug level, terminal visible, verbose render, safe-render bypass, and similar).
  - `user.workspace` in `getExternalFilesDir(null)`: current project, last room, window/panel state, UI Editor layout and recent items.
  - Project files stay in `projects/<name>/` (`ProjectStore`).
- `StudioPrefs` becomes a thin facade over `UserConfig`, so call sites don't change. A one-time migration reads the old SharedPreferences keys into the files. Route `StudioApp.kt:137-150`, `LoopEngine.kt:71`, `StudioApp.kt:163` and `ScreenRecordService.kt:182` (hard-coded `projects/default`) through the facade or one project-path resolver.
- `StudioRoom.kt` Profile tab → "Config" tab: auto-generated rows from the Config schema (Switch for Boolean, slider or stepper for numbers, chips for enums), grouped as Profile / Studio flags / Developer. Export (`StudioRoom.kt:146`) includes `user.profile` instead of the ad-hoc JSON.

## 4. Debug terminal: CLI-style, stdout only
- New `…/debug/Debug.kt`: `object Debug { fun e/i/d… }` with 3 levels (1 Errors and warnings, 2 Info and events, 3 Trace), reading the level from `user.profile` (0 = off). Writes to a ring buffer (~2000 lines, a Compose-state list) and mirrors to logcat (`Log`).
- Replace the ~14 raw `Log.*` calls (StudioApp, SafeRender, CameraRoom/Controller, LoopEngine, NodesLive, Bridge) and hook the silent `runCatching` sites and `ProjectStore.readOrNull` set-aside.
- Terminal view: a monospace, auto-scrolling, read-only overlay. No input field and no stdin. Opened from the Config tab or a developer toggle, as an overlay on any room. Add a Clear button.

## Scope note
The plan now covers about 25 items. A realistic split:
- **Tonight (pre-pilot):** 1-5, 5b-5d, the 5e core (A/B, palette, sensors, slit-scan, undo history, gestures), the Forge skeleton, the 5g "tonight" steps, 5f and 6.
- **After the pilot (roadmap, specced in ARCHITECTURE and PENDING):** scripting IDE, 3D module, Rosetta Code, the full Forge runtime and plugin sharing, the remaining math moves, and the 5e-ii suggestions.
The pilot checklist then tests what exists, and the roadmap items get their own sessions.

## 5. UI Editor module (descriptor registry + canvas)
There is an existing back-side foundation: `routing/` (`RoutingDoc`, `Router`, `RackOverview` drag gestures at `routing/RackRoom.kt:109`, `StudioBack.kt` Rtl mirroring and flip at `StudioApp.kt:724`). Node Lab stays untouched.

- New package `…/uieditor/`, registered as `Room.UiEditor` in `ui/StudioApp.kt` (enum ~108, `when(room)` ~900, room bar).
- `UiModel.kt`: `ControlDesc(id "room.control", kind {Slider, Knob, Button, Toggle, Chips, Preview, Readout}, label, rect(x,y,w,h), min, max, ...)`, `UiLayout(controls)`, and a `UiRegistry` that seeds default positions from the existing `ModuleBackSpec`, `KnobSpec`, `ButtonSpec` and `ModulePort` (`routing/StudioBack.kt:59-90`, specs at `StudioApp.kt:~730-850`) and `LabeledSlider` usages. Saved with the project (`ui-layout.json`, via `ProjectStore.file`), with the editor view state in `user.workspace`. Default seeding must not break rooms. Rooms still render themselves.
- **Front (layout editor):** one large pan/zoom canvas. Copy the `graphicsLayer` pan/zoom and grid from `nodes/NodesRoom.kt:~235-260`. Rooms are laid out as labelled panels. Drag, resize and snap controls. Each control draws as a faithful miniature of its kind (slider, knob, button).
- **Back (jack routing editor):** the flipped view (the existing flip animation and Rtl mirror) is an orthographic projection of the front. Every control becomes a port at the mirrored x-position (`x' = W - x - w`), with no slider or display. Controls of the same kind use the same jack kind. Drag a cable from any port to any other. Generalise `RackOverview`'s gesture and cable-drawing code and `RoutingDoc.connect` (currently `JackType` Canvas/Image only) with a `Value`/`Trigger` type for control ports. Persist cables in `ui-layout.json`.
- Runtime effect of the new cables is **out of scope tonight**: they are stored and validated, not yet driving rooms. Say so in PENDING.

## 5b. Colour channels in the 3D analysis tool (added)
`HeightTool` in `…/develop/DeepAnalysis.kt` (~351) shows brightness as a height mesh from `Analysis.kt` (`…/develop/model/`) and the scope's height grid (`Scopes.kt:33`).
- Add a colour-space chip row (RGBA / CMYK / Lab) and a channel chip row to the 3D tab: Luma (current), then the channels of the chosen space (R G B A, C M Y K, or L a b). Extend the height-grid builder to take a channel. Also offer an overlay mode, where the three channel meshes are drawn together tinted R/G/B with additive blending, to compare channels.
- Reuse the same channel enum in the Waveform tab (already has an RGB parade) so both tools agree.
- Unit test: channel extraction on a known 2x2 bitmap.

## 5c. Channels module: colour-space channel mixer per studio element (added, tentative)
Scope kept small tonight. Build the core module and wire it into a few elements, then extend. Colour spaces are **RGBA, CMYK and Lab**, selectable in the channel mixer.
- New package `…/channels/`. `enum ColorSpace { RGBA(R,G,B,A), CMYK(C,M,Y,K), LAB(L,a,b) }` with per-space channel names and ranges. `ChannelSet` holds the space and one plane per channel. `fun split(bitmap, space): List<Plane>` and `fun merge(planes, space): Bitmap`.
  - RGBA: direct from ARGB pixels.
  - CMYK: naive conversion (K = 1 - max(R,G,B), C/M/Y from the remainder), with a note that there is no ICC profile.
  - Lab: sRGB to linear to XYZ (D65) to Lab, and the inverse. Lab channels are float planes, so the split planes are `FloatArray`, not 8-bit bitmaps. Display previews map the range (L 0..100, a/b -128..127) to grey.
  - Per-channel gain, offset, blend (`Blend.android`) and mute/solo.
- Hook into the existing channel mixer: `MixerTab` in `…/develop/DevelopRoom.kt` and its model in `develop/model/Develop.kt` and `develop/Render.kt` (`renderDevelop`). Add a colour-space chip row (RGBA / CMYK / Lab) to the mixer, so the mixer's sliders are relabelled and applied per channel of the chosen space. Existing saved mixer settings load as RGBA.
- Per-element setup: a small `ChannelSetup` (space, per-channel gain/offset/blend/mute) that any studio element can own. Persist it in `ui-layout.json` for UI Editor controls and in the project files for the Develop, Compose and Camera elements.
- Wiring tonight: (1) the Develop mixer (above). (2) A Node Lab node pair, Split and Merge, in `nodes/model/Catalog.kt` (`standardNodes()`, Image to N Images by space), using `AndroidBackend.kt`. (3) Channel jack outputs on the Studio routing rack using the existing `JackType.Image`. Other elements get it via the same `ChannelSetup` later.
- The 3D analysis channel picker (5b) uses the same `ColorSpace` enum, so it also offers C/M/Y/K and L/a/b.
- Unit tests: RGB to CMYK to RGB and RGB to Lab to RGB round trips within tolerance, and known values (white L=100, black K=1).
- The Studio routing and UI Editor jack kinds get a `Channel` type so one image can fan out to four ports.
- Confirm with the user after tonight whether the module needs more places.

## 5d. Two-zone slider knob (added) — do this early, it touches every room
Replace the slider thumb in the shared `LabeledSlider` (`…/ui/Widgets.kt:53`) and the studio `Knob`, so all rooms and the Studio back get it at once.
- Shape: a small portrait rounded rectangle (about 28 x 44 dp drawn, with the touch area padded to 48 dp), split horizontally into two zones.
  - **Red, top: fine stepping.** Tap the right half for +1 step, the left half for -1 step. Hold to repeat, and the repeat accelerates by a decade each second held (x10, up to span/10). A small horizontal drag also steps, one step per ~8 dp.
  - **Blue, bottom: linear full-scale.** Drag along the track and the value maps linearly across min..max, the same as today's slider.
- Fine step size, the log10 question. Your idea is right: use the order of magnitude, with one correction. Use the **span**, not the current value:

  `step = 10^(floor(log10(max - min)) - 2)`, which gives roughly 100 to 1000 fine steps across the range.

  Worked examples: span 1.0 → step 0.01; span 255 → 1; span 360 → 1; span 2000 → 10; span 0.5 → 0.001 (floor(log10 0.5) = -1, then -2 → 10^-3).
  Why not log10 of the current *value*: log10 is undefined at 0 and for negatives, and the step would jump whenever the value crosses a power of ten (0.99 → 1.0), which feels broken. Use it on the value only for params that are genuinely exponential (frequency, zoom). Those params get an optional `log = true` flag on the slider spec. Then the blue zone also maps through `10^(t·log10(max/min))` and the fine step is a fixed ratio (e.g. x1.01).
  Integer params clamp the step to at least 1. Clamp the final value to min..max and round to the step grid, to avoid float drift (0.1+0.2).
- Add an optional per-slider `fineStep` override in the spec, and a long-press on the value text opens the existing `NumberDialog`.
- Unit test: step-size function over the example spans, rounding to the grid, and log-mapping round trips.

## 5e. Accepted from the suggestions (committed; your specs)

**A/B compare module (`…/abcompare/`).**
- It takes any routed input. Add A and B input jacks (`JackType.Image`/`Canvas`) on the Studio routing rack and in the UI Editor's back side. If nothing is routed, A is the live module output and B is a stored snapshot (a "Snap B" action).
- Hold gestures on any room canvas, by quadrant:
  - top-left: show the module's input state (what is routed in, with source label and thumbnail)
  - top-right: vertical split A | B
  - bottom-left: horizontal split, A over B
  - bottom-right: full A/B toggle
- The view shows while you hold and returns when you lift. Dragging while in a split moves the split line.
- Build it as one shared `Modifier.abHold(abState)` applied to the canvases in Draw, Compose, Develop, Animate, Camera, FFT and Glitch, so the rooms don't each grow their own copy.
- Conflict to resolve: Draw's lasso and stroke tools and the Compose brush already use touch on the canvas. The hold only fires after ~500 ms with less than 8 dp of movement and no tool stroke started. A per-user flag in `user.profile` can switch it to a two-finger hold instead.
- Unit test: quadrant hit-mapping, and the split-rect maths.

**Command palette in the notification shade (`…/palette/`).**
- A persistent, expandable notification. The collapsed view shows the current room and project. The expanded view has a custom `RemoteViews` layout with quick actions (capture, snapshot B, undo, jump to room), plus a `RemoteInput` inline search field: type a room, control or preset name and it jumps there. The search runs over the UI Editor's `UiRegistry` (section 5).
- Context-aware: the card updates its actions when you switch rooms, and when the app goes to the background it shows "return to <room>" plus the last actions.
- A Quick Settings tile (`TileService`) in the device control hub opens the palette or toggles capture.
- Platform limits:
  - Android "Device Controls" (`ControlsProviderService`) needs Android 11. Both phones run Android 13 or newer (minSdk 33), so Device Controls (Android 11+) are possible on both; the notification and the QS tile too.
  - Android 13+ (S21) needs the `POST_NOTIFICATIONS` runtime permission.
  - An on/off flag goes in `user.profile`.

**Sensors as sources everywhere.**
- Today: `patch/Sensors.kt` `SensorHub`, and Node Lab already has a `sensor` node (`nodes/model/Catalog.kt:127`, mapped Min..Max or Raw).
- Expand to a shared `SensorBus` with raw **and filtered** outputs per sensor: smoothed (one-pole low-pass), high-pass (shake and motion), derivative, threshold to Trigger, and a hashed **seed** output (sensor noise bits mixed into a 64-bit seed).
- Node Lab: add Filter/Seed variants to the sensor node.
- Studio routing / UI Editor: a Sensors module with Value, Trigger and Seed jacks, so any control port can take a sensor.
- Glitch and FFT labs: the "seed" field can be bound to a sensor seed source (corruption seeded by the world). Draw and Camera parameters take sensors via the routing jacks.
- **Record and replay** a sensor stream into the project (`sensors/<name>.json`), so a sensor-seeded result can be reproduced exactly.

**Slit-scan module (`…/slitscan/`, its own room).**
- Today: `animate/Barrier.kt` (`renderInterleaved`, `renderBarrier`) holds the barrier and interleave logic inside Animate.
- New room that takes frames from Animate, Sequence, a Camera burst, or any routed image stream. Settings:
  - slit orientation (vertical, horizontal or any angle) and slit width in px
  - scan direction
  - a time-offset map (a curve, a gradient image, or a routed input, which gives time displacement)
  - a "barrier" mode that reuses `renderInterleaved` so Animate keeps working
- Move the shared maths into `slitscan/model/` and keep Animate calling it.
- Output to the Library with lineage (source frames as parents).
- Unit test: slit sampling on synthetic frames, and parity with the current interleave output.

**Listable undo history (added).**
- Today: the generic `Undoable<T>` (`ui/Undoable.kt:12`) is used by Router and Node Lab, and Draw has its own snapshot stack (`DrawState.checkpoint`, `MAX_UNDO = 100`).
- Give every history entry a label and a time. Labels come from the action, for example "Move layer 3", "Group 2 layers", "Blend → Multiply". Move Draw onto `Undoable` (or a labelled adapter), so all rooms share one history type.
- A History panel per room, opened from a long-press on Undo: a scrollable list with the newest entry at the top and the current position marked. Tap an entry to jump there. Entries ahead of the current position stay in the list (greyed) until a new action, so you can redo by tapping as well.
- Each entry gets a small thumbnail where the room has a canvas, and the same list shows as a strip in the A/B module, so "B = any history entry" works.
- History lives in memory per session. An optional `user.profile` flag persists the last N labels to the project, for the lab notebook below.
- Unit test: jump back/forward, branch truncation on a new action, and label capture.

**Smart multi-touch and overlay menus everywhere (added).** Multi-touch on sliders already exists; this builds on it.
- One shared gesture layer (`…/ui/gestures/`, `Modifier.studioGestures`) so rooms don't each invent their own. The gesture map is configurable in `user.profile`, with a "gesture cheat sheet" overlay that you open with a four-finger tap.
- Default gestures:
  - two-finger tap: undo
  - three-finger tap: redo
  - three-finger swipe left/right: previous or next room
  - edge swipe from the right: History panel
  - two-finger long-press: A/B (if the user picked that option in 5e)
- **Sliders, second finger:** while one finger drags the blue zone, a second finger's distance from the first sets the precision decade. Closer means finer (x0.1, x0.01), so you get fine control without lifting. A second finger tap on the red zone snaps to the default.
- **Overlay menu on every registered control:** a long-press on any control in the `UiRegistry` opens a radial or overlay menu. It offers: type value (`NumberDialog`), reset to default, bind to a sensor or jack (5e), learn MIDI (later), add to A/B, show in the UI Editor, copy and paste value, and history for this control.
- Conflicts to resolve: canvas pan/zoom in Node Lab and the UI Editor, the Draw and Compose tools, and the A/B hold. Rule: tool gestures win with one finger, and studio gestures need two or more fingers or the edge zones.
- Unit tests for the gesture classifier (finger count, duration, movement thresholds).

**Module builder: modules, controller extensions and plugins (added; design and skeleton tonight, full build after the pilot).**
- New room, "Module Forge" (`…/forge/`). It builds on the UI Editor:
  - front canvas: lay out the module's controls from the `ControlDesc` kinds
  - back canvas: define its input and output ports
  - behaviour: a Node Lab sub-graph that wires signal inputs (sensors, jacks, controls) to outputs, using the existing `Graph`, `Evaluate` and `AndroidBackend`
- A custom module is layout + ports + graph, saved as `modules/<name>.module.json`. It appears in the room bar and on the Studio routing rack, drawn by one generic module renderer.
- Controller extensions: custom control widgets, built from existing control kinds and a mapping curve (for example an XY pad that drives 2 ports, or a macro knob that drives N controls).
- Plugins: package modules and extensions as `.studiomodule` zips, using the existing `Projects.export`/`import` zip code (zip-slip safe), to share between phones over Bridge or Loop.
- **No arbitrary code:** behaviour comes only from node graphs, so plugins are data, safe to import and fine with Play policy.
- Tonight: the data model, file format, the Forge room shell reusing the UI Editor canvases, and loading or showing a custom module on the rack. The generic runtime renderer and plugin sharing go into PENDING for after the pilot.

## 5g. Platform modules (added; spec now, build mostly after the pilot)
These five turn the studio into a platform. Each one is a multi-session build. Tonight they get a spec section in `docs/ARCHITECTURE.md` (§15-19), PENDING entries, and only the skeleton steps marked **tonight**.

**Forge GUI builder + Plugins tab.**
- Add a simple GUI builder to Module Forge: palette of widgets (all `ControlDesc` kinds plus Scope, Visualizer, XY pad, Keys, Meter, Canvas) → drag onto the module face → bind each widget to a port or graph node.
- "Wrap" packs a module into a defined plugin: a manifest (name, version, author, ports, widgets, graph, icon) in a `.studiomodule` zip.
- **Studio → Plugins tab** (new `StudioTab.Plugins`): lists installed plugins, with Install (import zip), Enable/Disable, Remove, and Details. Enabled plugins appear in the room bar, on the rack and in Node Lab as nodes. The installed list is stored in `user.profile`, and the plugin files live in `filesDir/plugins/`.
- Plugin types to support: scopes and visualizers (read a signal or image and draw it), interactive controllers (output values or triggers), and processors (image in, image out via the graph or a script).
- **Tonight:** the manifest format, the Plugins tab with install, enable and list, and one bundled sample plugin (a simple RGB-parade scope) to prove the path.

**Scripting module with an IDE.**
- Engine: an embedded interpreter. Kotlin can't be scripted on-device, so the recommendation is **QuickJS** (JavaScript, small, fast, sandboxable) via an Android binding. **LuaJ** (pure JVM Lua) is the fallback if the native lib is a problem. It is sandboxed, with no filesystem or network access. The API exposes only studio objects: `signal`, `image` (pixel arrays), `params`, `out`, `log` to the debug terminal (section 4), and the math library (below).
- IDE room: a code editor with syntax highlighting, line numbers, auto-indent and bracket matching. It also has autocompletion from the API, inline errors, run/stop, a live output panel tied to the debug terminal, a file tree per project (`scripts/`), snippets, and undo history (3).
- Scripts can be nodes in Node Lab (script node, with ports declared in the script header), processors in Forge plugins, and the overrides for math functions (below).
- This revises the "no arbitrary code" rule in Forge: plugins may contain **sandboxed scripts**, never native code or dex. Note: the Play Store is strict about downloaded executable code. Interpreted user scripts inside a private app are fine, but this needs checking before any public release, so it goes in DEV-NOTES.
- **Tonight:** none. This needs its own session (engine choice, the native lib on the S9+, the editor component).
- **Language: decide later** (user's call). The user wants many languages: JavaScript, Lua, Python, C#, Rust and C++, plus an option to add more. Design it as a **language-plugin interface** (`ScriptEngine`: compile/run/API binding/completions), so each language is an add-on. Notes for the decision session:
  - JS (QuickJS) and Lua (LuaJ) embed easily on Android.
  - Python means Chaquopy, or a Python build such as BeeWare/PyTorch's, which is a large APK size.
  - C#, Rust and C++ can't be interpreted the same way. Options are compiling them to **WebAssembly** off-device and running the `.wasm` in an embedded Wasm runtime (for example wasm3 or Chicory), or a remote build server. WASM as the common target for compiled languages is the most promising unifier.
- **Boilerplate library (the Blender principle):** a "Templates" menu in the IDE with a ready-made, heavily commented script for every extension point (new node, new scope, new plugin, new math override, new module, sensor filter, Rosetta task runner). It also has a **"Copy as script"** action on any control or node that writes the code that reproduces it, like Blender's Python tooltips and Info log.
  - **"Copy as script" is shown only when its config flag is on.** The flag is `dev.copyAsScript` in `user.profile` (Developer section of the Config tab) and is **off by default**. When the flag is off, the action doesn't appear anywhere: not in overlay menus, node menus or the palette.
- Vision note for DEV-NOTES, not a task: the user floated rebuilding the whole app in a few other languages or stacks, or making the app support other programming languages more deeply. Record it as a long-term question alongside the DAW decision.

**Low-poly vector 3D module.**
- A simple 3D room: primitives (cube, sphere-ico, plane, cylinder, extruded path from Draw), transforms, a camera (orbit and ortho/perspective), flat shading and wireframe. Rendered as vector polygons with the painter's algorithm, the same approach as `HeightTool` in `develop/DeepAnalysis.kt`, so there is no OpenGL dependency.
- Moiré angle: 3D gratings (line textures projected onto faces) to make moiré from geometry. Output as a Draw layer source, an image or SVG, and frames to Animate (turntable).
- Inputs: any parameter takes jacks and sensors (gyro → camera orbit).
- **Tonight:** none. Its own session.

**Math isolated and transparent + Parameters table.**
- Move every formula into `…/math/`, one readable file per domain: `Moire.kt` (from `draw/model/MoireMath.kt`), `Color.kt` (5c conversions), `Blend.kt`, `Filters.kt` (FFT, Glitch, Develop), `Geometry.kt`, `Sliders.kt` (5d step maths), `Signals.kt` (sensor filters).
- Each function gets a KDoc with the equation written plainly, the units, the parameter ranges and a source reference. Its tunable constants live in a `MathParams` registry (name, value, default, range, unit, description), not as magic numbers.
- **Parameters table** in the Config tab (section 3): every `MathParams` entry is searchable, grouped by file, editable, with reset-to-default, and saved in `user.profile`. Changes apply live.
- "Tinker with the source functions": Kotlin is compiled, so the source can't be edited at runtime. The honest version: each math function is shown read-only (its equation and source, embedded as text at build time), with an **optional script override** written in the scripting module. The default stays the compiled Kotlin, and an override can be switched off at any time.
- **Tonight:** a partial pass. Create `math/` and the `MathParams` registry, move the moiré, colour, slider and blend maths, and the Parameters table. The rest of the moves happen in later sessions. Script overrides wait for the scripting module.
- Unit tests: the existing tests keep passing after the moves, plus parameter defaults round-trip.

**Rosetta Code module.**
- A browser for Rosetta Code tasks (title, description, solutions per language), with offline data imported from the public Rosetta Code data dump on GitHub (e.g. `acmeism/RosettaCodeData`), or fetched per task from the site's MediaWiki API.
- Solutions in the scripting module's language (JavaScript or Lua) get "Open in IDE" and "Run". Other languages are read-only.
- Licence: Rosetta Code content is under the GNU FDL 1.3. Keep attribution and the licence text with any mirrored content. Fine for a private repo, but recheck before any public release (DEV-NOTES).
- **Tonight:** none. It depends on the scripting module.

**Calculator module (added; after the pilot).** A scientific and programmer calculator room with expression input, history (shares the undo history UI), variables, unit conversion, and hex/bin/oct. Results can be sent to any control or jack as a Value. It uses the `math/` library, so every function's equation is visible.

**Math module (added; after the pilot).** An explorable maths room built on `math/` and the `MathParams` registry: browse every function the studio uses, plot it (2D graph, and later 3D via the 3D module), change its parameters live, and see it applied to the current image or signal. It is the visual front-end of the math-transparency work and links to the IDE's script overrides and templates.

**FM/AM module (added; after the pilot).** Signal modulation as a core studio tool: carrier plus modulator oscillators (sine, saw, square, noise, or any routed signal or image row), AM depth, FM index and ring mod, with scopes and a spectrum view. Outputs:
- as signals or values to any jack
- as **image modulation**: FM or AM applied to gratings and pixel rows, which is moiré's own physics (beat frequencies are AM)
- as audio, if the DAW decision (5f) says yes
It ties together sensors, the FFT lab, the moiré maths and sonification.

**Gated triggers with detailed envelopes (added; after the pilot, Node Lab first).**
- Today: `nodes/model/Graph.kt` `SocketType` has Signal and Trigger but no Gate.
- Add **Gate** as a socket and jack type: on/off with a duration. A Trigger is an instant event and a Gate is held. Converters: Trigger→Gate (with a length) and Gate→Trigger (rising or falling edge).
- **Envelope generator** as a shared engine in `math/Envelope.kt` (transparent maths, `MathParams`):
  - shapes: AD, AR, ADSR, AHDSR, and multi-stage (N breakpoints)
  - curve per stage: linear, exponential, logarithmic or S, with a curvature amount
  - times in ms or synced to beats or frames
  - modes: retrigger, legato, loop and one-shot
  - velocity and level scaling, a delay stage, and invert
  - outputs: value, end-of-stage triggers, and the gate passed through
- An editable envelope graph widget (drag the breakpoints, using the 5d slider for exact values), so it can be added as a control kind in the UI Editor and Forge.
- Where:
  - Node Lab: Gate, Envelope, Gate logic (AND, OR, XOR, delay, divide and probability) and Sample & Hold nodes.
  - Every module: the overlay menu (gestures section) gets "Add envelope", so any parameter can be enveloped and gated from a trigger source (button, sensor threshold, beat, capture event, A/B switch).
  - Studio routing and UI Editor: Gate jacks.
  - Ties into FM/AM, sensors and the DAW question.
- Unit tests: stage timing, curve shapes at their endpoints, retrigger and legato behaviour.

**2D physics module (added; after the pilot).**
- Imported library. Recommendation: **dyn4j**, which is pure Java, BSD-licensed, maintained and runs on Android with no native libs. The alternative is JBox2D (a pure-Java Box2D port, older), or libGDX's Box2D (native and heavier).
- A Physics room:
  - bodies: circles, boxes, polygons, and shapes taken from Draw layers or strokes
  - joints, springs and motors
  - materials: friction, restitution and density
  - gravity from the accelerometer (sensors in 5e), so tilting the phone tilts the world
  - collision events as Triggers and Gates, which can drive envelopes
- Outputs: body positions, angles and velocities as Values on jacks. The rendered frames go to Animate or Sequence and as Draw layers, so you can get physics-driven gratings and moiré from moving, colliding line fields. It can also feed the 3D module later.
- The fixed-timestep simulation is deterministic per seed, which fits record/replay and the lab notebook.

## Naming and vocabulary themes (added; plan later, ships at release)
- **"Afterpilot" is a reserved name** for a future module, plugin or extension. It is **not** tied to the Calculator, Math or FM/AM modules. What it names is decided in the naming session (DEV-NOTES 1c/1d). Record it in DEV-NOTES under "Reserved names".
- **Vocabulary themes.** The app's naming theme is currently Moiré (the default, "Moiréic"). At release, the Studio → Theme config page gets a **Vocabulary** option that renames rooms, modules, tools and terms by theme:
  - Moiréic (default)
  - Gaussian, Bayesian, Fourieric, Euclidian, Pythagorean, Eulerian, Newtonic
  - non-Newtonic, non-Euclidian
  - more added over time
  - Each theme needs its own naming, worked out in the vocabulary session.
- Technical groundwork, a prerequisite and its own session:
  - Move all user-facing strings out of the Compose code into a keyed vocabulary layer (`…/vocab/`). Each key has a default (Moiréic) string, and a theme is a mapping file (`vocab/<theme>.json`) that overrides keys. Missing keys fall back to the default.
  - This is the same mechanism as translation to other languages, so build it once for both.
  - Plugins (Forge) ship their own vocabulary keys.
  - The chosen theme is stored in `user.profile`.
- Naming note to settle in the session: "moiré" comes from the French name for watered-silk fabric (*moiré*), not from a scientist. The theme can still be "Moiréic", but the manifesto and About text should get the origin right.

## PR / announcement material (added)
Keep a `docs/PR-PIPELINE.md` for public-relations material (release notes, store text, announcements). The first entry is the user's line, kept verbatim:
> "So now it's naming theme is taken from the scientist Moiré, but at release there will also be a vocabulary option in the studio's theme config page. Where the users can select Gaussian, Bayesian, Fourieric, Euclidian, Phythagorian, Euleresian, Newtonic and not to forget non-Newtonic, non-Euclidian vocabularies and of course the default Moiréic and a finite many more to come with e time to the power of pi I."

Polish it for publication later. Correct the "scientist Moiré" origin, and the e^(iπ) joke (= −1) can stay as a wink.

## 5e-ii. Further suggestions (all committed; lower priority, after the items above)
Quality of life
- **Haptic detents** on the slider's red zone at each decade boundary, so you can feel the step change.
- **Crash-safe recovery banner**: if the app died with unsaved state, say so on start and offer the autosave.

Creative data processing
- **Moiré fringe predictor** in Camera and Draw: from two gratings' pitch p1, p2 and relative angle θ, draw the predicted beat pattern live. The beat period is `p1·p2 / sqrt(p1² + p2² − 2·p1·p2·cosθ)`. It is the core physics of the app and it makes the overlay a measuring instrument.
- **Pixel-sort and orientation-field tools** in the Glitch lab: sort by luminance along a path, and visualise the local gradient direction (structure tensor), which is what moiré interacts with.
- **Colour transfer / histogram matching** between two Library assets, in the Lab space from 5c.

Out of category
- **Print-ready calibration sheets**: export a barrier-grid or lenticular pitch test sheet (SVG or PDF) at exact physical size, calibrated to the S9+'s pixel pitch, so you can build real optical filters for the screen.
- **Studio as a lab notebook**: every capture and render automatically writes its parameter set plus a one-line note into a reproducible journal. "Rerun this experiment" restores all state. The recipe/lineage system (`library/Lineage.kt`) already holds half of this.
- **Sonification**: turn an image row, a histogram or the FFT lab output into audio. This ties in directly with the DAW question in 5f.
- **MIDI / OSC in and out**, so external gear or a laptop can drive any registered control.

## 5f. Project dev notes: pre-release decisions and reminders (added)
Create `docs/DEV-NOTES.md`, a short, dated notes file for the pre-release stage, with a **"Decide before release"** section holding:
1. **Sound / DAW decision.** Decide whether the studio brings in sound processing and works as a DAW too. Questions to settle: audio engine (Oboe/AAudio) and the latency budget on the S9+, whether audio becomes a new signal type in the Patch bay and Node Lab (`SocketType` already has Signal), whether it is a new room or a module in the Rack, and how sonification (5e) feeds into it. This is a decision, not a build task.
1b. **Language and stack question (long-term):** how many scripting languages to support (JS, Lua, Python, C#, Rust, C++, plus more via WASM), and whether to ever rebuild or port the app to other stacks. Also recheck the Play policy on scripts and the Rosetta Code licence (GFDL) before any public release.
1c. **Philosophy design session.** Hold a dedicated session to define the application's manifesto and philosophy. Starting material is the Vision section of this plan: the "unified frequency" statement, the Blender principle, simple harmonic to hypercomplex. The result is a `docs/MANIFESTO.md` that guides naming, vocabulary themes and what goes into the studio.
1f. **Name "Module Forge":** this is the working name, kept by the user, for the builder of modules, controller extensions, plugins and the GUI builder. Confirm or rename it in the naming and vocabulary session.
1e. **Forked Moiré game engine branch (future, undecided timing).** At some future point, fork a game-engine branch from the studio. It would build on the physics module, 3D, scripting, gated triggers and envelopes, sensors and Forge plugins. Decide when, and whether it is a branch, a fork or a separate app, after the release. Note only, no task.
1d. **Naming and vocabulary design session.** Decide how names tie into the naming themes and internal vocabulary (see "Naming and vocabulary themes" below), including where **Afterpilot** fits.
2. **Next dev session after bootstrapping and the pilot test: set up a scheduled agentic workflow for the project, and research how.** Starting points for the research: Claude Code routines (`/schedule`) and recurring loops (`/loop`, cron triggers), what they could run unattended (build + unit tests, checklist and changelog consistency, dependency checks, a nightly pilot-feedback digest), and what needs a human.
Make sure you can't miss them:
- Put a "Decide before release" banner at the top of `docs/PENDING.md` (read at the start of every session per memory) and at the top of `docs/studio-log.html`, linking to DEV-NOTES.md.
- Show the same two items in the Studio About tab as a "Pre-release notes" card.
- On execution, save a project memory entry for both items, so I raise them at the start of the next sessions without being asked.

## 6. Docs, log and checklist (every step)
Keep, don't reset: update `docs/studio-log.html` (`ROOMS` items for camera, draw, studio, uieditor, debug, plus a new `LOG` entry with date, time, title and test count), `docs/TESTING.md`, `docs/PENDING.md`, and `docs/ARCHITECTURE.md` (§14 UI Editor, plus config files and debug levels). Update the studio log artifact as in memory (studio-log-page). README only if the file layout changed. Do not mark any item as passed; no user tests have been made.

**Feedback fields in `docs/studio-log.html` (added).** Today each checklist item has a hidden note box behind the ✎ button (`renderRooms`, line ~479) and the state is `{s, n}` in `localStorage` and the Firestore `checks` collection (`save`, line 452).
- Make the item note always visible as a one-line field that expands as you type. Keep the ✎ button as a collapse toggle, and add a dot on the button when a note exists.
- Add a free-text "Feedback" box per room (at the top of each `details`), a general "Overall pilot feedback" box at the page top, and a feedback box on each changelog entry in the Changelog tab. Store them under the same `checks` store with ids like `room:<id>`, `general` and `log:<date>-<time>`, which use the existing `save`, `loadLocal` and debounce.
- Add a "Copy all feedback" and "Download JSON" button that exports status plus all notes, so the feedback can be pasted into a session or filed in the repo.
- Keep the stable item ids and the existing stored data compatible.

**Repo handoff.** After the pilot, the project goes into its own private GitHub repo. Before then, add a `.gitignore` check (build outputs, `local.properties`, keystores, personal profile data), and note in the README that `user.profile` and `user.workspace` are runtime files and not committed. No push or repo creation happens without the user's say-so.

## Verification (S9+ only, via adb; see memory test-phones-adb)
1. `./gradlew testDebugUnitTest assembleDebug`. Add unit tests: `compositeOverlay` pixel results per blend mode, `LayerGroup` effective visibility and nesting, in-place duplicate positions, group-id remap, `UserConfig` round-trip and migration, UI mirror maths and cable type rules.
2. Install on the S9+ and run the matching checklist items:
   - Camera: take a shot with a grating overlay (Normal and Multiply). Open it in Library and confirm that the overlay is in the saved image.
   - Draw: group, nest, collapse, show/hide all, and duplicate a group (the copy lands exactly on the original).
   - Config: toggle a flag, restart, and check `user.profile` (adb `run-as`) and `user.workspace` (the external files dir).
   - Debug terminal: levels 1, 2 and 3 output, and no input field.
   - UI Editor: move and resize on the canvas, flip to the back, drag jacks, restart and confirm persistence.
3. Update the log and checklist with the build time. Commit after each numbered section if the user asks.

## Addendum 2026-10-23: added before building continues
Four items from the user, plus one clean-up. Nothing here is built yet. Details are in PENDING.md under "Added 2026-10-23".

**A. Gated triggers and envelopes (already planned, now last in the queue but fully specified).** The envelope maths exists (`math/Envelope.kt`, tested), with no screen and no Gate socket. To build, in order:
1. `SocketType.Gate` in `nodes/model/Graph.kt` with the converters Trigger to Gate (with a length) and Gate to Trigger (rising or falling edge).
2. Node Lab nodes: Gate, Envelope, Gate logic (AND, OR, XOR, delay, divide, probability), Sample and Hold.
3. The editable envelope graph as a control kind in the UI Editor and Module Forge.
4. "Add envelope" in the overlay menu of every control, with trigger sources (button, sensor threshold, beat, capture, A/B switch).
5. Gate jacks on the rack and in the UI Editor's back side.

**B. Subtract blend.** `Blend` (`draw/model/Layers.kt`) has Normal, Lighten, Multiply, Difference, Screen, Darken, Overlay, SoftLight, HardLight, Exclusion, Add, ColorDodge, ColorBurn, Hue, Saturation, Color and Luminosity, and no Subtract. (Subtract exists only for Develop masks and as a Node Lab maths operation.) Add `Subtract` (below minus above, per channel, never below zero) and `ReverseSubtract` (above minus below), next to Add. Android and Compose have no such blend mode, so it needs an AGSL `RuntimeShader` (API 33, the app's minimum) in the layer compositor, with the same maths in the CPU export path so the preview and the saved file agree. It applies wherever `Blend` is used: Draw, Compose, the camera overlay burn-in (`OverlayComposite.kt`) and Node Lab's blend node. Unit tests: pixel results, opacity, and clamping at zero.

**C. A layout that scales and stays in bounds, especially in landscape.** Today the controls stretch across the full width, so in landscape a slider track is about 1800 px long and the panel fills the screen. The user's screenshots show this, and a pop-up (the Stretch Y number box) sitting over the picture.
- Panels and sliders get a maximum width (about 560 dp for a control panel; a slider track about 480 dp) and stay inside the safe area and any camera cut-out.
- In landscape the canvas keeps the larger share of the screen and the controls sit in a bounded side panel instead of a full-width strip. In portrait they stay at the bottom.
- The panel can be scaled by dragging its edge. The size is remembered in `user.workspace`, per orientation.
- Dialogs (`NumberDialog` and the like) are bounded too. My reading of the user's arrows: the number box should open inside the control panel's own footprint, not float over the canvas. To confirm with the user before building.
- The UI Editor keeps a separate layout for portrait and landscape.
- Tests: a width-class function (compact, medium, expanded) that decides the layout; the clamping maths.

**D. App icon: show more of the picture.** The icon art is a full-bleed bitmap in the adaptive icon's foreground, and the launcher mask crops it to about the middle two thirds. Put the art inside the icon's safe zone (an inset of about 14 %), and give the background layer the same art, blurred or zoomed, so the edges are not black bars. The brighter version of the art stays.

**E. Phone-agnostic: remove the Samsung and test-phone references.** Found in the code today:
- `AndroidManifest.xml` `<queries>` names `com.sec.android.gallery3d`, `com.samsung.android.app.notes` and the FV-5 packages.
- `bridge/model/Bridge.kt` hard-codes "Samsung Gallery" and "Samsung Notes" with those package names.
- Strings that say Samsung: the Bridge room description (`ui/StudioApp.kt`), the Develop style tab and its "Send to Samsung Gallery" button (`develop/DevelopRoom.kt`), and comments in `ArtStyles.kt`, `Develop.kt` and `ScreenRecordService.kt`.
- Comments that name the test phones (`AndroidManifest.xml`, `camera/CameraState.kt`, `glitch/GlitchRoom.kt`).

The change:
- Bridge targets come from intents ("any app that can view, edit or take an image"), listed by the label Android reports, not by a name in our code. Package visibility uses `<queries><intent>` entries, not named packages.
- A user-editable list of favourite target apps in `user.profile`, with an empty default. FV-5 stays as an optional detected app, labelled from `PackageManager`.
- Heap and size limits come from `ActivityManager.memoryClass` instead of an assumption about one phone.
- The Samsung and S9+/S21 names stay only in the docs about testing.
- Check: a grep for `samsung`, `com.sec`, `S9+` and `S21` in `app/src` finds nothing outside tests.

**Order.** E and D are small and go first. Then B (subtract blend), then C (the layout), then A (gates and envelopes).
