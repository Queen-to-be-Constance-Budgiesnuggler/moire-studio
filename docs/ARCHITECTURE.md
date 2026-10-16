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

**Glitch lab** — byte-level stride/width/channel/chroma/plane-fill reinterpretation (raw bytes, not floats); re-encode generations; parameter sweep wall.

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

## 12. routing-nodes (stage 3)

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

