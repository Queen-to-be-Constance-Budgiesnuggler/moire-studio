# Pending

> **Decide before release:** whether sound processing joins the studio (a DAW mode); the long-term question of scripting
> languages and stacks; the naming and vocabulary themes ("Afterpilot" is a reserved name; "Module Forge" is a working name).
> **Next dev session after the pilot test:** set up a scheduled agentic workflow for the project, and research how.
> Notes: [DEV-NOTES.md](DEV-NOTES.md). The plan these came from: [PLAN-pre-pilot.md](PLAN-pre-pilot.md).
> How the local model is used for coding: [AGENT-WORKFLOW.md](AGENT-WORKFLOW.md).
> The scheduled sprint works through [SPRINT.md](SPRINT.md); run reports in SPRINT-REPORT.md.

Open items after build steps 1–8 (2026-10-14). Tick them off here as they land.
Hands-on testing checklist for the whole app: [TESTING.md](TESTING.md).

## Sprint open items (2026-10-26)
- [ ] R06: the 3D room keeps no scene across restarts and has no undo or routed inputs; faces behind the camera are skipped, not clipped; depth order is per face; scaled meshes are rebuilt on every redraw (cache them); the Save SVG button writes to Download/Studio (drop it if Library-only is wanted); `tools/feedback-server/server.py` has "3D" in its room list, which needs the same change on the Pi at the next deploy.
- [ ] S17: tested over loopback only; not yet sent between two real phones. Left: no Save all before a project is packed; one picture at a time; no progress in the Library. Review point: `IncomingFiles.end()` uses `check(renameTo)`, which throws if the rename fails (the caller is inside `withContext(Dispatchers.IO)`; check how that error reaches the UI).
- [ ] S18: new sub-folders created while the app is open are not picked up until the next resume; the Pictures watcher is not recursive; reviewer point: the RunGate hook is redundant (the collectLatest already restarts the flow on resume) but harmless.
- [ ] S15b: clone/heal only works on paint layers (photo layers need the source bitmap, not the paint sheet); heal is colour-shift only (no Poisson blending); the clone offset stays locked for the whole session; a non-paint layer selected via undo or add can still have Clone/Heal active briefly.
- [ ] S15: imported .ttf fonts; the `.cube` file is read on first use in the Compose render path (`ComposeRender.kt`), to move off the main thread under S02.
- [ ] S08: plugin behaviours are a list editor only (no Node Lab canvas); plugin outputs are not Rack jacks; a shared file still needs Studio → Plugins → Install; built-in plugins have no Share button.
- [ ] S08 review points: `PluginBehaviourTest` covers image and save nodes but no sensor node; `PluginRoom.kt:82` takes the whole pause as `dt` on resume (smoothing jumps once), reset `lastT` on resume or cap `dt`; `PluginStore.kt` share cache is never cleaned.
- [ ] S04 review points (APPROVE, 2026-10-26): a per-frame `(0..2).map` in the Switch node (`Evaluate.kt:214`); the Touch Pad's Down is 0 inside a group (say so in the help); a muted Touch Pad may pass nothing; `setProps` on every pointer move.
- [ ] Test-phone model strings are in two test files (`FeedbackTest.kt`, `LinkTest.kt`): the rule is no Samsung or test-phone names in `app/src`, so move them to a neutral string.

## Built 2026-10-21, not tried on a phone yet
Everything here compiles and its logic is unit-tested; none of it has been used by hand. The checks are in TESTING.md
("Build of 2026-10-21") and in the checklist page.
- [ ] Camera: overlay burnt into the photo with a blend mode (Loop captures are not burnt).
- [ ] Draw: nested, foldable groups with rows of tabs that branch downwards; show/hide all; duplicate in place.
- [ ] History lists (Draw, Compose, FFT, Glitch, Node Lab, UI Editor) and the gestures (two/three/four-finger taps, three-finger
      swipe, right-edge swipe).
- [ ] The two-zone slider thumb on every slider.
- [ ] Studio → Config (user.profile, user.workspace, flags, developer options, math parameters), debug terminal.
- [ ] Develop channel mixer (RGBA, CMYK, Lab) and the 3D analysis channels.
- [ ] UI Editor (front layout, back jack routing), Plugins tab and Module Forge.
- [ ] A/B compare in Draw and Develop, with an A/B unit on the rack.
- [ ] Slit-scan room (from Animate frames), sensor node outputs (High-pass, Change, Seed).
- [ ] Checklist page: note fields everywhere, room and overall feedback, copy all.

## Added 2026-10-23 (plan addendum in PLAN-pre-pilot.md; do these before building more)
- [x] E. Phone-agnostic (2026-10-23): no Samsung or test-phone names in `app/src`; Bridge photo editor and share go through Android's chooser, the gallery opens via the gallery category; installed on the S9+, launches, no crash. Left: a user-editable favourites list in `user.profile`, and memory limits from `ActivityManager.memoryClass` (the 2048 px glitch cap is still a constant).
- [x] D. App icon (2026-10-23): art inside the safe zone (14 % inset) over a blurred copy; seen only as a desktop preview, not yet on a launcher.
- [x] B. Subtract and reverse-subtract blend (built 2026-10-23; on the S9+ the two chips are in Compose's Mode row and Subtract changed the picture, not compared pixel by pixel): done on the CPU in `blend/SubtractMath.kt` (no shader needed) for Compose layers, the camera overlay burn-in and the Node Lab Mix node. Draw does not offer it: Draw paints vector objects straight onto a canvas, which cannot subtract. A Draw-layer version needs a bitmap pass; open item.
- [x] C. Layout (built 2026-10-23, seen on the S9+ in Compose): `ui/layout/RoomLayout.kt`. Landscape puts the controls in a scrolling side panel (default 420 dp, 280 to 560 dp, at most 60 % of the width), with a draggable edge whose width is kept in `user.workspace` (`panelWidthDp`). Portrait keeps the panel under the picture, at most 640 dp wide. The exact-value box opens inside the panel. In Draw, Compose, Develop, FFT, Glitch, Animate, Sequence, Camera, Loop, Patch and Slit-scan; all eleven opened in landscape on the S9+ without a crash (screenshots looked right; not every control was driven). Left: Library, Bridge, Node Lab, Studio, UI Editor and Forge keep their own layouts; a separate layout per orientation in the UI Editor; the Knob dialogs still use a full dialog.
- [ ] A. Gates and envelopes, half done (2026-10-23): built and unit-tested: the Gate socket (cyan), and the Node Lab nodes Gate Switch, Trigger to Gate, Gate to Trigger, Gate Logic, Gate Delay, Gate Divide, Gate Chance and Envelope (ADSR with curves, from `math/Envelope.kt`); the gate maths in `math/Gate.kt` came from the local model (13 of 14 tests on the first try; the delay's history trim was fixed by hand). On the S9+ the nodes appear in Node Lab's Add menu and a Gate Switch can be added; wiring and a live run were not done. The editable envelope graph is built (Node Lab: select an Envelope node, "Envelope…"; a control kind in the UI Editor and Forge), unit-tested, seen on the S9+ (dragging a handle updates the node). "Add envelope" is built too (long-press a slider's name; the Gate button or a clock is the trigger; a sensor threshold waits for the sensor bus), not yet seen on the phone. Gate jacks are built too (Value, Trigger and Gate jack types, the Studio gates on the Studio's back, Node Lab Signal inputs and the Module Signal node, typed ports in the UI Editor), not yet seen on the phone. Left: nothing in this item but the phone checks.

## Feedback diagnostics (built 2026-10-24; see [FEEDBACK.md](FEEDBACK.md))
- [x] (done 2026-10-24 by the user) `sudo tailscale funnel --bg --https=8443 http://127.0.0.1:8760` (sudo asks for a password, so it was not done remotely). Until then reports queue on the phone.
- [x] Opt-in switch and levels (Off, Basic, Full) in Studio → Config, first-run question, "See what would be sent", note, delete my data. Seen on the S9+ against a test server: session report, forced crash, digest.
- [x] Collector on `deb-server-1` (`~/MoireFeedback`, user service `moire-feedback`, `127.0.0.1:8760`); nightly job pulls `docs/FEEDBACK-DIGEST.md`.
- [ ] Not tried: the HTTPS address, "Delete my data" and "Write a note" on a phone, Wi-Fi-only on mobile data, the first-run question on the S21.
- [ ] Decide before inviting testers: whether the first-run question should show for pilot users (it does now), and who may read the reports (only the Pi's owner, over ssh).
- [x] Checklist tests done in daily use (2026-10-24): tap probe, catalog (82 of 260 tests; 56 strong, 26 weak), `server.py evidence`, badges on the checklist page. Seen on the S9+ for two tests.
- [ ] Catalog quality: review `tools/usecases/REVIEW.md`; add hand-made entries in `overrides.json` for tests whose generated labels are loose; gestures, visual results and slider values cannot be detected. S16 (2026-10-25): 15 hand-made entries added (17 in all), checked against the source; the digest lists rooms never opened, buttons never tapped and sliders never moved. About 60 loose tests remain, and the digest is not yet run on the Pi.
- [x] (fixed 2026-10-24: "Use Tailscale DNS settings" off on the phone; tailnet names now need IP addresses there) S9+ could not resolve host names over its Tailscale connection (reports queue and retry); check the Tailscale app's DNS setting on the phone. Not an issue for testers outside the tailnet.
- [ ] Later: sensor-free "feature never used" lists, a crash symbol map (builds are not minified, so stack traces read directly).


## Not built yet (from the 2026-10-21 plan)
- [x] UI Editor cables now drive the real controls of the module backs (2026-10-23, not yet seen on the phone). Decision: the layout stays an editor-only arrangement; rooms keep their own (bounded) layout and do not draw themselves from it.
- [x] Channels (2026-10-23): Split and Merge nodes, the Channel splitter on the rack, the three-channel overlay in 3D; built and unit-tested, surfaces seen on the S9+, live chain not yet. Left: per-element channel setups (queue item Q5b).
- [x] Moiré fringe predictor on screen in Draw (2026-10-23, seen on the S9+). Camera: not applicable, see QUEUE Q8.
- [x] Command palette in the notification shade and a Quick Settings tile (2026-10-23; the notification and its room jump seen on the S9+; typing into "Go to…" and the tile not driven). Android Device Controls (the hub) are not built.
- [x] A/B in Compose, FFT, Glitch and Animate (2026-10-23; the hold gesture is not yet driven on the phone). Camera is left out: its preview cannot be blended.
- [x] History lists for Animate, Sequence and Develop (2026-10-23; generic labels, see QUEUE Q7).
- [x] Sensor bus with record and replay, sensor jacks on the rack, and a sensor seed in Glitch (2026-10-23; seen on the S9+ up to a 7.2 s recording; the seed effect not yet driven). FFT has no seed, so none there. A sensor threshold as an envelope trigger is built (S10, 2026-10-25; unit-tested, not yet on a phone). Its background pause was a stopgap; S01 (2026-10-26) now pauses the sensors and mic through the one run gate.
- [x] Slit-scan from Library images (a camera burst) and a routed stream (2026-10-23; Library seen on the S9+, stream not driven). A Sequence source is not done. Barrier mode still shares code with Animate's interleave.
- [~] Plugins: the Plugins room, plugin controls as module backs for UI Editor cables, and Loop role and host in `user.profile` are built (2026-10-23).
- [ ] Plugins, still open: node-graph behaviour behind a plugin's controls; sharing a plugin over Bridge or Loop; the RGB parade as a real scope plugin.
- [x] Math parameters (2026-10-23): the slider timings plus sensor, envelope, gate, layout, A/B and fringe parameters are wired. Other formulas (blend, filters, grating maths) keep their numbers inside the functions.
- [ ] "Copy as script" flag has no scripting module behind it.
- [x] Haptic detents on the slider and the crash-recovery banner (2026-10-23).
- [x] Print-ready pitch test sheet, lab notebook tab, "Hear this line" sonification and OSC in (2026-10-23). MIDI in and out is built (S09, 2026-10-25; see the MIDI line below). Notebook notes and rerun are built (R08, 2026-10-25); see the line below. OSC out: the encoder, a UDP sender and an "OSC out" rack unit (A to D as /studio/a to /studio/d, host and port in Config) are built and unit-tested (2026-10-24, 429 studio tests pass). Not yet tried on a phone; not yet patched to a control.
- [x] MIDI in and out (2026-10-25, S09): a "MIDI in" unit (CC 0 to 127 or note velocity on jacks A to D; Gate on while a key is held; channel filter) and a "MIDI out" unit (jacks A to D sent as CC on one channel, only on change). Unit-tested; not yet tried on a phone with a keyboard. Open: MIDI mappings and channel settings reset on restart; Bluetooth MIDI devices need a scan that is not built; only the first port of each device is used; the device is closed when the studio is in the background, as the sensor bus is (not yet S01's general pause).
- [x] Lab notebook notes and Rerun (2026-10-25, R08): notes on each entry; "Rerun" on Draw, Compose, Develop and Glitch saves makes a linked new entry. Unit-tested (19 + 3 tests); not yet on a phone. Open: no Rerun for FFT, Animate, Sequence, Node Lab, Math or captures; notes do not show in the Library.
- [x] Layout leftovers (2026-10-25, S11): slit-scan frames from the Sequence timeline; a UI Editor landscape front (its own panel positions; cables shared; "Like portrait" resets); Knob exact-value boxes inside the back's panel; landscape side panels for Library, Node Lab and Studio. Unit-tested (25 tests); not yet seen on a phone. Open: a node's number box still opens a full dialog; toolbar rows in landscape scroll sideways; UI Editor zoom and pan are shared between the two fronts.
- [x] FM/AM nodes (2026-10-25, R04): an FM/AM node in Node Lab's Time section (AM, FM or Ring; Carrier and Modulator Hz, AM depth, FM index, Min and Max; Value, Carrier and Modulator outputs) and an FM/AM Image node in the Filter section (bands or a line shift across a picture). Unit-tested (28 tests); not yet tried on a phone. Open: no rack unit, since Node Lab has no number outputs to the rack yet; no FM/AM curve in the Math room; no sound (D2).
- [x] Math room (2026-10-25, R03): six curve functions (linear, exponential, logarithmic, S-curve, clock gate, sensor average) with a plot against y = x and sliders with Reset; applied as a tone curve to a Library image (RGB or one channel, Mix, Before) and saved as a child image. Unit-tested (20 tests); not yet tried on a phone. Open: the saved image has no recipe, so the settings do not reopen; images only come from the Library.
- [x] Calculator room (2026-10-25, R02): expressions, history (100 lines), variables, units, hex/bin/oct, a result sent to a control or a Result jack. Unit-tested (73 tests); not yet tried on a phone. Open: history and variables are lost on restart; results go to knobs only; areas and volumes are not units.
- [ ] After the pilot: scripting IDE (language undecided), low-poly 3D, Rosetta Code, math and FM/AM modules, 2D physics,
      the vocabulary themes. "Afterpilot" is a reserved name, not tied to any of them.

## Devices
- S21: the user's phone, for hand testing. Do not drive its screen.
- S9+: the agent's phone for builds and checks (adb over Tailscale, port 5555). Check the foreground app before any tap.
- Both were last given a full build before the 2026-10-21 work; install a fresh one before testing.

## Untested on a phone (unit-tested or built only)
- [ ] Compose: masks, adjustments, generator parameters, pinch-zoom view, transparent background PNG.
- [ ] Draw image layers (+ Image), Animate "+ Library frames".
- [ ] Bridge: editor-app and notes-app round trips (send → edit → save → inbox → import as child). Brand names go away with item E.
- [ ] Bridge: screen recording (consent dialog was cancelled in testing, not approved).
- [x] Sequence: 120 Hz lock on the S21 works (mode 1, 120 Hz override; 758 vsyncs, 0 dropped).
- [ ] Camera: manual exposure, bursts with each stack mode, RAW/DNG, on a real capture.
- [ ] Loop: free-run mode, torch strobe, Sequence as source.
- [ ] Patch: hardware sensors, microphone, sensors from the other phone.
- [ ] Develop: curve editor, masks (lasso, brush, spot), .cube import/export, style mix, presets.
- [ ] Glitch: saving a result; JPEG generations with corruption on device.

## Requests 2026-10-17 (before the big test round)
Small and quality of life:
- [x] Redo in every room (next to Undo).
- [x] Layers (Draw, Compose): multi-select (hold), groups (Compose: real layer groups blended as one; Draw: object
      groups picked together), hold-and-drag to reorder.
- [x] Camera: zoom (pinch on the preview + exact value; recorded with the capture).
- [x] Brushes: Compose showed strokes only after lifting the finger (fixed: live stroke preview); straight lines
      for the Draw pen and the Compose brush / eraser / mask.
- [x] Lasso selections copy / paste: Draw had it (Copy/Paste after a lasso); Compose: Copy / Cut / Paste of a
      selection as an exact clipped layer.
- [x] Animate: Save GIF (holds kept, at the Speed setting).
- [x] Projectors leave the phone's brightness alone; Sequence has a Projector brightness setting.
- [x] Glitch: error correction (amount, scale 1/1–1/32, every N generations).
Bigger:
- [x] Develop: histogram + vectorscope; mask stack (lasso, brush, spot, luminance; add / subtract / intersect /
      difference; opacity); blend mode + opacity for the edit; Style tab with 10 artistic styles + amount.
- [x] Library light table: pinch grid, stars / flags / colour labels (also in batch), sort, filters, loupe with
      swipe and zoom, side-by-side compare.
- [x] Stage 4, module routing: the Rack room (front: live outputs and source pickers; flip: jacks and cables),
      live outputs from Draw, Animate, Compose, Develop, FFT, Glitch, Camera, Loop, Node Lab into Compose
      layers, lab sources, the camera overlay and Node Lab; loops refused; routing-nodes renamed Node Lab.
      Spec: docs/ARCHITECTURE.md §13.
- [x] Rack, later (2026-10-25): Sequence / Loop projector inputs and the Draw image-layer input (S07a), Patch signals as jacks and rack presets (S07b). Unit-tested (22 tests); not yet on a phone. Open from S07b: the LFO jacks run on their own clock, not the patch's; route jacks follow list order; presets are per project, not app-wide. Open from S07a: an unplugged live Draw layer stays as a grey box until deleted; the Sequence projector is not in the GIF or MP4 export; Draw's canvas publish may run before a decoded rack picture is set (reviewer note, to check on a phone).

## Requests 2026-10-18
- [x] Develop: 3D height map in the scope cycle; final step: deep analysis card (probe, line, region, clipping,
      waveform / RGB parade, 3D).
- [x] Studio routing moved into Node Lab: the whole app flips to its mirrored back; module backs with jacks,
      knobs, buttons and module presets; All cables overview. The Rack tab is gone.
- [x] Studio module: projects (file menu), preferences, themes (Gruvbox, Dracula, Nord, Solarized, Monokai,
      One Dark, Tokyo Night, Catppuccin), profile, about.
- [x] Themes reach canvas-like areas (2026-10-25, S12): the node editor canvas and nodes, Touch Pad strip, and the rack
      and studio-back panels use the theme's colours; Studio (default) looks as before. Open: the UI Editor's back still
      uses its own dark colours; the cable plug centre is now #0B0B0C (was #222222).

## Known issues
- [ ] S02 left: Studio's "Save all now" writes eight JSON files on the main thread (`StudioApp.kt`); preview settings go to the camera from the main thread on every slider move; SlitScan calls `router.inputBitmap` while drawing (`StudioApp.kt`); Plugins and UI Editor read plugin files inside `remember`; the pitch-sheet SVG is written on the main thread. The 23:27 freeze is not reproduced.
- [ ] S02 review points: `CameraRoom.kt` catches `CancellationException` in `runCatching` around `controller.open`, so leaving Camera can show "Camera: null"; `CameraController.kt` creates `jr` and `rr` before the `try`, so a throw in `rr` leaks `jr`; the session wait after a disconnect could use a timeout.
- [ ] R05 left: the Physics room is not saved with the project; changing bounce, friction, frame rate or walls restarts the world; no touch on the canvas; lines have no thickness; no per-body hit jacks; the frame rate is not passed to Animate. The ARCHITECTURE.md section for the physics module is not written yet.
- [ ] Glitch room knobs "Width Î”" and "Stride Î”" show mojibake (`ui/StudioApp.kt`, the Glitch panel); the text should read Δ. Predates the sprint.
- [ ] Glitch filter on a Compose layer ignores a colour image: the layer path cannot reach the Library (R09 left-over).
- [ ] R09: turning "Sort angle" switches Pixel sort on even when a modulator moves it; confirm that is wanted.
- [ ] S01 tidy-ups from review: a missing space after `&&` in two `MidiBus.kt` checks; `RunGateTest` uses real delays (could flake on a slow machine); the microphone thread handover is untested (needs Android); the camera preview and screen-recording service are not gated yet.
- [ ] S01 battery: the drain (416 mAh with the screen off) is not re-measured; hand check `s01-battery`.
- [ ] BLOCKED on the user: 2026-10-15: user reports "many things not reacting throughout the app" (seen on the S21). Fixed so far:
      FFT brushes (paint only worked on Magnitude/Phase, Boost/Keep invisible, Boost ×0 under a filter),
      Glitch out-of-memory crashes on 12 MP photos (S9+, 03:00 and 03:04), lab hangs on unreadable images,
      Grab from Draw silently failing, Library losing its place. Still need: which other controls, and the
      S21's logcat (`adb logcat -b crash` and `-s Studio`) once it is connected.
- [x] One FFT record on the S9+ had the summary "FFT 0" from a fixed label bug: cosmetic, old data, closed.
- [x] The Loop run that started at 16:30 on 2026-10-14: no code path starts a run alone; a person starting the linked phone (its Start message starts this camera) explains it. Closed.

## Quality of life, not done yet
- [x] Camera: "Last in Library" (2026-10-23).
- [x] Animate: frame thumbnails in the strip (2026-10-23).
- [x] Library: delete several picked images, and delete an image with everything made from it (2026-10-23; built and unit-tested, not driven on the phone).

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
- [x] Touch pad node (the Patch bay's TouchX/Y), Smooth node, Switch node (2026-10-25, S04; not yet on a phone). Open: Touch Pad's Down is not carried into groups; Switch takes numbers only, not images.
- [x] 5. Node Wrangler: lazy connect, lazy mix, preview any node, batch blend, swap A↔B, reroutes (tool +
      Add menu), auto-insert on links, align, box select. Fixed: lazy connect used the touch-down point
      as the drop point. Unit-tested; not yet tried by finger on a phone.
- [x] 6. Presets (save/load/delete + built-ins), Frames (label, colour, move with nodes, resize, remove),
      node groups (group, enter/exit, Group Input/Output with + sockets, shared definitions, ungroup,
      nested groups, per-use time state). Unit-tested; not yet tried by finger.
- [x] Groups: remove or reorder a group socket; group presets (save one group to reuse in other projects) (2026-10-25, S05; unit-tested, hand checks in TESTING.md, not yet tried by finger). Open: sockets are edited only from inside the group; no renaming a socket; group presets are not shared to other phones; adding one preset twice makes two copies.
- [x] 7. Two-phone nodes: Send to Other Phone (Live stream or once), From Other Phone (also Loop
      captures), Projector (full screen), remote sensors. Both phones need this build (new link message).
      Unit-tested; S9+ installed; S21 was off adb (new port needed).
- [ ] Room internals as default graphs (open a room's own routing as a graph).
- [x] Compose layer stack as a node (2026-10-25, S06: "Compose Stack" in Node Lab, four image inputs that feed Compose's layers; unit-tested, not yet on a phone). Open: single layers' opacity, blend or visibility are not node inputs; the Node Lab preview refreshes only when you return to it.

## Spec items not built yet
- [ ] §6 Sync: no file transfer between the phones; each phone has its own project.
- [x] Draw: high-res render mode (2×, 3×, 4096 px, 6000 px next to Save; 2026-10-25, S13; unit-tested, not yet on a phone). Open: the size is not used by the rest of the studio (Animate frames, routing, Loop, Library previews); image layers gain no detail when scaled up.
- [x] Animate: barrier print export at a chosen DPI / paper size (2026-10-25, S14; not yet printed). Open: the recipe keeps the animation but not the paper or dpi; a phone may run out of memory at A4 and 300 dpi (check it).
- [ ] Bridge: watch folders only while the app is open (no background service).
- [x] Draw: stroke rendering cache (2026-10-25, S03; unit-tested, hand checks in TESTING.md, not yet on a phone). Open: a cache rebuild paints on the main thread (see S02); the cache planner's own tests do not cover undo after a bake or a visibility toggle in the middle of the stack.
