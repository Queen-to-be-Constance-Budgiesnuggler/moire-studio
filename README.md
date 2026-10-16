# Moiré Studio

Two-phone experimental image studio (S21 + S9+). Hub for Samsung Gallery, Samsung Notes and Camera FV-5, plus its own bundled tools.
Full spec: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Status
Build step 1 (Draw + Library/lineage) is usable:
- **Draw**: grating / radial-burst generators and hand-drawn stroke objects with stroke stacks
  (per-line width, ink, dash/gap/phase, offset, cap) and saved transforms (move, rotate, uneven stretch).
  Tools: Move (drag / twist / pinch, tap to select), Pen (presets: Ink, Rails, Dashed, Dash beat), Lasso.
  Copy / paste / duplicate, undo, blend per object, beat and dash-beat readouts. The canvas autosaves.
- **Library**: Save renders the canvas screen-exact to PNG (also exported to `Pictures/Studio/`) with a lineage
  record (parents, operation, full recipe, device, time). Detail view shows parents/children, A/B flicker
  against the parent, and Open in Draw to re-run a branch; the next save becomes a child.
- Data lives in the app's private `files/projects/default/` (`lineage.json`, `canvas.json`, `animation.json`, `sequence.json`, `assets/`).

Build step 2 (Animate) is usable:
- **Frames**: each frame is a full canvas recipe. Grab from Draw, Replace from Draw, Edit in Draw; duplicate, delete,
  reorder, hold counts; onion skin; play at 1–60 fps. The frames autosave to `animation.json`.
- **Moving a layer**: drag, twist or pinch the chosen layer (Move chips) to edit the current frame. With **Rec drag** on,
  a frame is captured every 1/fps while you drag. The **step helper** adds N frames, each shifted/turned by a fixed
  step, and warns when a grating moves more than p/2 per frame (aliasing limit).
- **Barrier** (scanimation): interleaves all frames in vertical strips (strip width 1–12 px, slit spacing = N × strip),
  shown pixel-exact with a sliding slit overlay (drag sideways or Play). Save writes the interleaved image and the
  printable barrier (transparent slits) to Library and `Pictures/Studio/`; Library can reopen them in Animate.

Build step 3 (Sequence + projector) is usable:
- **Timeline** of Animate frames (timing kept) and Library images; durations in display ticks at a fixed rate
  (60 Hz, or 90/120 Hz where the screen has them), Loop / Ping-pong / Once, black-frame insertion.
- **Projector**: full screen, max brightness, screen on, display mode locked to the sequence rate. All frames are
  decoded before the clock starts and shown 1:1; the frame is picked from the vsync timestamp, so a late frame
  never shifts the rest. Closing it reports display Hz, vsyncs and dropped vsyncs.
- **Export**: GIF (one global palette, no dithering, changed-rectangle frames; delays rounded on the running
  total) and H.264 MP4 at a constant rate up to 60 fps. Both go to Library (`sequence.gif` / `sequence.mp4`,
  parents = the Library images used) and to `Pictures/Studio/` or `Movies/Studio/`.

Build step 4 (Bridge + watch folders) is usable:
- **Send to** from Library: Samsung Gallery editor (EDIT), Samsung Notes (SEND), or any app (chooser). Bridge can
  also just open Gallery, Notes or FV-5 (FV-5 has no control API: launch, then watch its folder). Every send is
  remembered for 30 minutes.
- **Inbox**: while the app is open it watches shared storage (MediaStore) for new images from other apps and
  matches each one to a send by time and by the app that wrote it. Unambiguous matches can be imported in one go;
  uncertain ones ask first. Imports are copies (`external:<app>`), children of what was sent.
- **Share target**: images shared into the studio are imported; if the sharing app is one you just sent to, they
  become children of that send.
- **Screen recording** (MediaProjection, consent every session) saves to Library and `Movies/Studio/`.
- Needs "Allow all" photo access; with only selected photos, new images from other apps stay invisible.

Build step 5 (Camera + Loop) is usable:
- **Camera**: Camera2 with manual shutter (log slider), ISO, focus distance, aperture where the lens has more than
  one (S9+: f/1.5 and f/2.4), white balance presets, torch; burst up to 8 with the software optical stack
  (additive = sum × gain, clipped; mean; interlace = rolling-shutter model, row r sees frame
  floor((t0 + r·t_row)/T_frame)); RAW as a DNG child of the JPEG; preview overlay of a grating, the Draw canvas or
  the Animate barrier (not burnt in). Settings are the capture's recipe: Library → Open in Camera restores them.
  FV-5 opens from here (launch + watch folder via Bridge).
- **Loop**: two phones over a TCP link (port 47800, Tailscale or LAN; type the other phone's address on one of
  them). The Projector phone shows a frame full screen; the Camera phone captures it with its Camera room settings
  and sends it back; it is filed as `loop.capture`, child of the frame on screen. Next frame = the capture
  (feedback, generation after generation) or the next Sequence item. Synced (settle, capture, next) or free-run
  (independent timers); optional jittery torch strobe on the camera phone.

Build step 6 (Patch bay) is usable:
- **Sources**: rotation (heading, pitch, roll), accelerometer X/Y/Z, magnetometer strength, ambient light,
  microphone level (level only; nothing is recorded), proximity, a touch pad (drag on the live canvas), two LFOs
  (sine, triangle, square, saw, random sample-and-hold). Any hardware source can be read from the other phone
  while the two are linked in Loop (run Patch live on both).
- **Routes** drive a parameter of a Draw layer (grating spacing/angle/offset/line width; burst rotation/rays/
  width/centre; stroke position/rotation/stretch) with their own min/max (reversible), curve (linear,
  exponential, logarithmic, steps) and frame-rate-independent smoothing. Sensors only run while a route uses them.
- **Record to Animate** appends the canvas as Animate frames at the Animate frame rate while live; one Undo in
  Animate removes the whole take, and one Undo in Draw takes back everything a live session changed.

Build step 7 (Develop) is usable:
- Exposure (in linear light), temperature/tint, levels (black, white, mid-tones), a monotone tone curve (drag,
  tap to add, long-press to remove) and an 8-band hue mixer (hue, saturation, lightness; neighbouring bands blend).
  All of these bake into one 33³ colour table, so the live preview is fast and **Export look as .cube** is exact.
- **.cube import** (3D, any size, DOMAIN honoured) with an amount slider; files are copied into the project.
- **Masks**: lasso, brush or spot colour (hue range with a soft edge, greys excluded), feathered and invertible.
- **Style mix 0–100**: blend in another Library image, e.g. the result of Samsung Gallery's AI Style (send it
  there from the Style tab, import the result from the Bridge inbox, pick it).
- **Presets** keep the look (not the image, mask or style). Hold "before" to compare. Save renders full resolution
  (PNG stays PNG) as a `develop.render` child of the source; Library → Develop starts an edit, Open in Develop
  reopens one.

Build step 8 (FFT lab, Glitch lab) is usable:
- **FFT lab**: the image (centre square, 256/512/1024) as its spectrum: log magnitude (gray / heat) or phase
  (cyclic map), DC centred. Low/high/band-pass filters plus painting on the spectrum (erase, keep, boost),
  mirrored through the centre so the result stays real; inverse per RGB channel. **Phase from** another image
  swaps magnitude and phase. Saves the result at the working size as a child of both images.
- **Glitch lab**: misreads the raw RGBA bytes (bytes per pixel 1–4, row width, stride, offset, channel order),
  shifts chroma against luma, fills byte ranges (zero, max, invert, repeat), then JPEG re-encode generations with
  optional seeded corruption of the scan data (headers untouched, so it still decodes). The **sweep wall** shows
  9 variants of one parameter around the current value; tap one to take it. Works at up to 2048 px.

Library records are small; each recipe is its own file (`assets/<id>.recipe.json`), so stroke-heavy animations
don't bloat the index. Older inline recipes are moved out on first load.

All eight build steps from docs/ARCHITECTURE.md §9 are in. Since then (review pass):
- **routing-nodes** (stage 3, steps 1–3 + part of 5): a node-based studio router, Blender node editor style.
  Nodes: Library Image, Draw Canvas, Value; Grating / Radial Burst / Ellipse Hatch generators; Render Canvas,
  Math, Map Range, Transform; Develop, Mix (17 blend modes), Invert, Desaturate, Posterize, Threshold; Blur,
  Sharpen, Noise, FFT Filter, Glitch; Viewer, Save to Library, Send to Compose. Colour-coded typed sockets
  (Image yellow, Canvas orange, Value grey, …). Drag socket → socket to link, drag a linked input to pick the link
  up, drop on a node to auto-connect; drag nodes; pinch/pan; long-press for the searchable Add menu; double-tap
  frames all. Tools: Select, Lazy connect (Node Wrangler), Cut links (knife). Mute, collapse, duplicate,
  delete, batch blend for selected Mix nodes. Value fields: tap for an exact number, drag sideways to scrub.
  The backdrop previews the Viewer or any node (◎). Results are cached per node, so changing one value only
  recomputes what is downstream. Save / Send to Compose run full size and file the graph as the recipe
  (Reopen recipe brings the graph back). Presets: save, load, delete, plus built-ins (Grating over photo,
  Moiré pair, FFT texture, Glitch screen blend). Library → Use in → routing-nodes adds Library Image nodes.
  **Time nodes (step 4, the Patch bay as a graph):** ▶ Live runs the graph every display frame. Clock (seconds,
  step, phase, tick), LFO (5 shapes, min/max, phase), Sensor (every Patch bay sensor, mapped and smoothed, on
  this phone or the linked one), Sample & Hold, Animate Frames / Sequence Frames + Frame at (play a set with a
  Clock), Layer Canvases (vector stack with a blend), Drive Layer (set one layer parameter from any number:
  a Patch route), Record to Animate (arm it; records the canvas each Trigger or at Animate's fps; one Undo in
  Animate removes the take). Number nodes key the image cache by value, so repeating values reuse images.
  Live presets: LFO sweeps a moiré, tilt to turn a grating, LFO drives Draw layer 1, play Animate frames.
  **Node Wrangler tools (step 5):** Box select, Lazy connect, Lazy mix (drag between two picture nodes: a new
  Mix of both), Cut links, Add reroutes (swipe across links: a reroute dot on each; dots carry whatever flows
  in and take its colour), auto-insert (drop an unlinked node on a link to put it in between), Align (row or
  column), Swap A↔B. Patch bay → Open as graph turns the routes into Sensor/LFO → Drive Layer nodes
  (one Undo in routing-nodes brings the previous graph back).
  **Frames and groups (step 6):** Frame (Ctrl+J) puts a tinted, labelled box around the selection; drag its
  label bar to move it with its nodes, its corner to resize; rename, recolour or remove it (nodes stay).
  Group (Ctrl+G) turns the selection into a node group: links crossing it become the group's sockets, and a
  Group node takes its place. ⇥ (or Enter group) opens it, Blender style, with Group Input / Output nodes;
  link from Group Input's + (or into Group Output's +) to add a socket. Every use of a group shares its
  insides; groups appear in the Add menu under Group; Ungroup puts the nodes back (fields typed on the
  Group node go to the inner fields). Groups can hold groups (never themselves). The backdrop and Run
  always work on the top graph.
  **Two phones (step 7):** link the phones in Loop, then Send to Other Phone streams its input to the other
  phone while Live (up to Max fps, at its Size, JPEG or PNG) or sends once; From Other Phone shows the latest
  picture received (or the latest Loop capture) with a Count that ticks up; Projector shows its input full
  screen at full brightness, live while Live runs. Sensor nodes read the other phone with Phone: Other.
  Presets: stream a live moiré to the other phone; project what the other phone sends.
- **Compose** (the main editor, GIMP-style, touch-first): a layered document of any size (from a layer, the
  screen, or typed) on white, black or transparent. Layers: Library images, Draw canvas snapshots and live
  generators (grating, sine, burst, ellipses; transparent between lines). Per layer: show/hide, opacity, 17 blend
  modes, move/pinch/twist or exact X/Y/scale/stretch/rotation, Fit/Fill/1:1, raise/lower/duplicate/delete,
  painted masks (hide/reveal, size, softness, invert) and Develop adjustments. View: pinch-zoom and pan; tap picks
  the topmost layer. Save renders full size to Library (parents = every image used; Reopen recipe brings the
  stack back).
- **Compose stage 2**: tools View, Move, Select (rectangle), Lasso, Brush, Eraser, Mask, Eyedropper.
  New layers: paint (vector brush strokes), text, fill, linear/radial gradient. Blending options: 17 modes,
  opacity, clip to the layer below, blend-if (this layer or underlying, shadows/highlights/feather).
  Per-layer filter stack: blur, sharpen, noise, invert, desaturate, posterize, threshold, FFT pass, Glitch.
  Selections: crop, keep/cut out as mask (feathered), fill/erase on a paint layer. Layer strip with thumbnails,
  rename, flip. Colour picker: swatches, HSV and opacity with exact values.
- **Library is the hub**: every image's page has **Use in** → Draw (image layer), Animate (frame), Sequence
  (item), Develop, FFT, Glitch, Camera (preview overlay), Loop (the projector's first frame), Patch (placed in Draw,
  ready for routes). Long-press in the grid to pick several (tap to add more, in order), then send them to
  Draw / Animate / Sequence / Patch at once, or tag them. Every arrival is undoable in its room.
  "Reopen recipe in …" still reopens how an image was made.
- **Sequence framing**: frame shape (Screen, First item, 1:1, 4:3, 3:4, 16:9, 9:16) and per item Fit / Fill / 1:1
  and Turn 90°. Preview, projector and exports share one path; the projector scales down, never crops.
- **Library images in the canvas**: Draw → **+ Image** places Library images as layers (move, twist, pinch,
  corner-stretch, opacity, blend mode; Fit / Fill / 1:1). They travel with the canvas into Animate frames,
  the barrier, Sequence, the Camera overlay and Patch routes, and saved results list them as parents.
  Animate → **+ Library frames** turns picked images straight into frames.
- **Draw**: New canvas; layer Up/Down; ellipse hatch generator; sine-profile gratings and "+ Sine pair";
  drag a selected stroke's corner handle to stretch it unevenly (along its own axes).
- **Library**: filter by kind and by tag; tags; delete (only images without children; removes the shared
  copy too); compare with any image (A/B flicker); direct parents plus the ancestry chain; "View in…".
- **Every room**: opening something from Library (or Animate → Edit in Draw) is undoable, so it never loses
  unsaved work. The last room is remembered. The room bar shows what runs in the background (Patch live,
  Loop running or linked, export progress, screen recording, inbox count).
- Heavy previews (Develop, Glitch, FFT, barrier) stop as soon as a newer edit arrives.

Open items: [docs/PENDING.md](docs/PENDING.md).

## Version control
Local Git repository, branch `main`, no remote. Build output, `local.properties`, IDE folders and
`.recovery/` are ignored. Useful commands: `git log --oneline` (history), `git diff` (uncommitted changes),
`git restore <file>` (drop changes to a file). Changelog and test checklist: `docs/studio-log.html`.

## Requirements (Windows)
- Android Studio 2026.1 (installed at `C:\Program Files\Android\Android Studio1`; the `Android Studio` folder next to it is a broken leftover)
- `JAVA_HOME` = `C:\Program Files\Android\Android Studio1\jbr` (JDK 25; Gradle 9 will not start on the Java 11 on PATH)
- Android SDK with platform 35 + build-tools
- `adb` on PATH (`%LOCALAPPDATA%\Android\Sdk\platform-tools`)
- VS Code extensions: see `.vscode/extensions.json` (VS Code prompts on open)

## One-time setup
1. `local.properties` holds the SDK path (Android Studio writes it on first sync).
2. Enable USB debugging on both phones (S9+ on LineageOS: Settings → About phone → tap Build number 7× → Developer options).

## Build and run (VS Code)
- `Ctrl+Shift+B` → **Build debug APK**
- Terminal → Run Task → **Install on connected phone**
- Or in a terminal: `.\gradlew.bat installDebug`
- Unit tests: `.\gradlew.bat testDebugUnitTest`

## Versions
AGP 9.4.1 (built-in Kotlin) · Gradle 9.6.0 · Kotlin 2.2.10 · Compose BOM 2024.09.03 · minSdk 33 (S9+ LineageOS 13) · targetSdk 35
