# Pending

Open items after build steps 1–8 (2026-10-14). Tick them off here as they land.
Hands-on testing checklist for the whole app: [TESTING.md](TESTING.md).

## Devices
- [x] S21: current build installed 2026-10-14 20:49 (steps 1–8 + review pass).
- [x] S9+: camera access was off in quick settings; it is on again (19 camera captures in its Library).

## Untested on a phone (unit-tested or built only)
- [ ] Compose: masks, adjustments, generator parameters, pinch-zoom view, transparent background PNG.
- [ ] Draw image layers (+ Image), Animate "+ Library frames" (S9+ was locked when installed, S21 offline).
- [ ] Bridge: Samsung Gallery editor and Notes round trips (send → edit → save → inbox → import as child).
- [ ] Bridge: screen recording (consent dialog was cancelled in testing, not approved).
- [x] Sequence: 120 Hz lock on the S21 works (mode 1, 120 Hz override; 758 vsyncs, 0 dropped).
- [ ] Camera: manual exposure, bursts with each stack mode, RAW/DNG, on a real capture.
- [ ] Loop: free-run mode, torch strobe, Sequence as source.
- [ ] Patch: hardware sensors, microphone, sensors from the other phone.
- [ ] Develop: curve editor, masks (lasso, brush, spot), .cube import/export, style mix, presets.
- [ ] Glitch: saving a result; JPEG generations with corruption on device.

## Known issues
- [ ] 2026-10-15: user reports "many things not reacting throughout the app" (seen on the S21). Fixed so far:
      FFT brushes (paint only worked on Magnitude/Phase, Boost/Keep invisible, Boost ×0 under a filter),
      Glitch out-of-memory crashes on 12 MP photos (S9+, 03:00 and 03:04), lab hangs on unreadable images,
      Grab from Draw silently failing, Library losing its place. Still need: which other controls, and the
      S21's logcat (`adb logcat -b crash` and `-s Studio`) once it is connected.
- [ ] One FFT record on the S9+ (amv8ft77t-487b12) has the summary "FFT 0" from a fixed label bug.
- [ ] A second Loop run started at 16:30 on 2026-10-14 without a tap from the test script; confirm it was a
      person at the phone, otherwise investigate how `LoopEngine.start` got called.

## Quality of life, not done yet
- [ ] Redo (every room has Undo only).
- [ ] Camera: jump from the last capture to its Library entry.
- [ ] Animate: frame thumbnails in the strip (numbered chips today).
- [ ] Library: multi-select (tag or delete several at once); delete a whole branch.

## Compose, stage 2 (built 2026-10-16)
- [x] Layers: paint, text, fill, gradient; thumbnails, rename, flip.
- [x] Blending options: 17 modes, clip to layer below, blend-if.
- [x] Tools: select, lasso, crop, brush, eraser, eyedropper, fill/erase selection, selection masks.
- [x] Filters per layer: blur, sharpen, noise, invert, desaturate, posterize, threshold, FFT, Glitch.
- [ ] Untested on a phone: selections, crop, blend-if, clipping, each filter, gradients, eyedropper.
- [ ] Later: layer groups, per-layer full Develop (mixer, curve, LUT), healing/clone brush, transform handles
      on the canvas, text fonts.

## Stage 3: routing-nodes (spec: docs/ARCHITECTURE.md §12)
- [x] 1. Graph model + evaluator (typed sockets, lazy, cached), Value/Math/Map Range.
- [x] 2. Editor canvas: nodes, bezier links, link by drag, add menu, pan/zoom, frame all, backdrop.
- [x] 3. Room nodes: Library Image, Draw Canvas, generators, Develop, filters, FFT, Glitch, Mix, Transform,
      Viewer, Save to Library, Send to Compose.
- [x] 4. Time nodes: Clock, LFO, Sensor (local/remote), Sample & Hold, Animate/Sequence Frames, Frame at,
      Layer Canvases, Drive Layer, Record to Animate; ▶ Live runner (S9+: ~18–23 fps on the LFO moiré).
      Tested on S9+: Live LFO sweep, Record (90-frame take, one Undo removes it), Animate playback.
      Not yet tested: Sensor nodes on a phone, Phone = Other over the Loop link, Sequence Frames.
- [x] Patch bay room: "Open as graph" (routes → Sensor/LFO → Drive Layer chain; curves other than Linear
      and touch pad routes are noted on the node, not carried over).
- [ ] Touch pad node (the Patch bay's TouchX/Y), Smooth node, Switch node.
- [x] 5. Node Wrangler: lazy connect, lazy mix, preview any node, batch blend, swap A↔B, reroutes (tool +
      Add menu), auto-insert on links, align, box select. Fixed: lazy connect used the touch-down point
      as the drop point. Unit-tested; not yet tried by finger on a phone.
- [x] 6. Presets (save/load/delete + built-ins), Frames (label, colour, move with nodes, resize, remove),
      node groups (group, enter/exit, Group Input/Output with + sockets, shared definitions, ungroup,
      nested groups, per-use time state). Unit-tested; not yet tried by finger.
- [ ] Groups: remove or reorder a group socket; group presets (save one group to reuse in other projects).
- [x] 7. Two-phone nodes: Send to Other Phone (Live stream or once), From Other Phone (also Loop
      captures), Projector (full screen), remote sensors. Both phones need this build (new link message).
      Unit-tested; S9+ installed; S21 was off adb (new port needed).
- [ ] Room internals as default graphs (open a room's own routing as a graph).
- [ ] Compose layer stack as a node.

## Spec items not built yet
- [ ] §6 Sync: no file transfer between the phones; each phone has its own project.
- [ ] Draw: high-res render mode (only screen-exact).
- [ ] Animate: barrier print export at a chosen DPI / paper size.
- [ ] Bridge: watch folders only while the app is open (no background service).
- [ ] Draw: stroke rendering cache (large stroke-heavy canvases redraw every frame).
