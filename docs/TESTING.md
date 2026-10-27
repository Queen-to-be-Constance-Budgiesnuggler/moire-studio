# Testing checklist

Everything in Moiré Studio that still needs a person to try it on a phone. Most of it has unit tests,
or was checked by a script over adb, but nobody has used it by hand yet. Each item says what to do
and what you should see. Tick items off as they work. For anything that doesn't, note the room, the
phone, what you did, and what happened.

**Phones:** S21 = Android 15. S9+ = LineageOS 13. Both have the build from 2026-10-16.
**Most important:** the "Unresponsive controls" section below. That is your report from 2026-10-15.

---

## Unresponsive controls (open bug)
- [ ] In each room, try every button, chip, slider and field once. Note any that do nothing, or react
      only after a delay. Fixed so far: FFT brushes, Glitch crashes on big photos, Grab from Draw,
      Library losing its place.
- [ ] Number fields: tap the value next to a slider and type an exact number. The slider should jump
      to it. The − / + / −− / ++ buttons should nudge it.
- [ ] If something crashes: tell me the time. I'll read the log when the phone is connected.

## Library (the hub)
- [ ] Long-press an image in the grid, tap more to add them, then send them to Draw, Animate,
      Sequence or Patch. All of them should arrive, in the order you picked.
- [ ] Use in → each room: Draw, Animate, Sequence, Develop, FFT, Glitch, Camera (overlay), Loop
      (first frame), Patch, Compose, routing-nodes. The image should arrive in that room, and
      Undo there should remove it.
- [ ] Reopen recipe from a saved Draw, Animate, Develop, FFT, Glitch, Compose and routing-nodes
      result. You should get back how it was made.
- [ ] Tags: add, filter by tag, remove.
- [ ] Delete: possible for an image without children; refused for one with children.
- [ ] Compare with another image (A/B flicker), and the parents / ancestry chain on the detail page.

## Draw
- [ ] + Image: place a Library image, then move, twist, pinch, drag a corner to stretch, change
      opacity and blend mode, and switch Fit / Fill / 1:1.
- [ ] Ellipse hatch generator, the Sine profile, "+ Sine pair".
- [ ] Select a stroke and drag its corner handle: it should stretch along its own axes.
- [ ] New canvas, layer Up / Down, Undo after each.
- [ ] Save: the image should appear in Library and in Gallery (Pictures/Studio).

### Draw: stroke rendering cache (S03)
- [ ] On a canvas with about 200 strokes, draw a new stroke slowly: it follows the finger and the strokes under it look the same.
- [ ] Pick a grating under many strokes and drag its Spacing slider: the grating changes live and the strokes above it stay on top.
- [ ] Set a layer's blend to Subtract (if offered), draw a stroke and undo: the picture matches what it was before.
- [ ] Add a Library image, leave Draw and come back: the grey box turns into the image.
- [ ] Rotate the phone or resize the split in Draw: the canvas redraws at the new size, nothing stale.

### Themes on the canvas areas (S12)
- [ ] Pick Nord, open Node Lab with a few nodes: canvas, grid and node bodies are Nord blue-greys; a selected node has a light blue border.
- [ ] Pick Gruvbox light, open Node Lab, select a node and drag a selection box: light cream canvas, dark text on the nodes; the selection is Gruvbox orange-brown.
- [ ] Pick Dracula, open Node Lab → Studio routing, open All cables and one module: panels and labels use Dracula colours; cables keep their type colours.
- [ ] Pick Studio (default), open Node Lab and the rack: both look as before (dark grey canvas, orange selection).
- [ ] Pick Catppuccin Mocha, add and select a Touch Pad: the pad strip uses the theme's raised surface with light cross lines.

### Animate: barrier print export (S14)
- [ ] In Animate's barrier view with 2 or more frames, pick A4 and 300, press Save: the line reads "A4 at 300 dpi: 2480 × 3508 px…" and both saved files are 2480 × 3508 (3508 × 2480 for a wide canvas).
- [ ] Pick A4 and 600: the line turns red, shows about 498 dpi and says "600 dpi is too big for one image"; the save still works.
- [ ] Pick Screen and save: the dpi chips are greyed out and the files are the canvas size, as before.
- [ ] Save at A5 and 150, print both files at 100 %, and lay the barrier over the image: the slits line up with the strips.

### Node Lab: Touch Pad, Smooth, Switch (S04)
- [ ] Add a Touch Pad with Min 0, Max 360; link X to a Grating's Angle; select it, press Live and drag on the pad: the grating turns and one Undo takes back the whole drag.
- [ ] Link Down to an Envelope's Gate and the Envelope to the Grating's Line width: the lines thicken while you hold the pad and thin out after you let go.
- [ ] Put a Smooth (Rise 1 s, Fall 0) between Touch Pad X and the Angle: the angle drifts up over about a second but snaps back at once.
- [ ] Add a Switch (A 0, B 45) with a Gate Switch on Select: Pick jumps between 0° and 45°; Fade moves smoothly.
- [ ] In the Patch bay, add a Touch Y route with some smoothing and tap Open as graph: Touch Pad, Smooth and Drive Layer appear, with no "(no touch pad yet)" label.

### Envelopes: sensor trigger (S10)
- [ ] In Draw, long-press a slider's name, "Add envelope…", Trigger: Sensor, Light: the G button goes away and the hint says the envelope starts when the sensor rises above the level.
- [ ] With the sensors off, tap "Switch sensors on": "Light now: NN %" appears and changes as you cover and uncover the light sensor.
- [ ] Set Level to the middle of what Light reads, then cover and uncover the sensor: the slider follows when the light rises above the level and goes back when it drops below.
- [ ] Set Band to 30 % and hold the light near the level: the slider does not flicker.
- [ ] Choose "Falls below": the envelope starts when you cover the sensor.
- [ ] Turn the sensors on and press Home for a minute: the sensors and mic switch off in the background and come back on return.

### Node Lab: group sockets and group presets (S05)
- [ ] Group two nodes with links in and out, open the group, tap "Sockets…", press ↓ on the first input: the input moves down on the Group node outside, its link moves with it, and the picture stays the same.
- [ ] In "Sockets…", tap Remove on a Value input that has a number typed on the Group node, then Undo: the socket and its link go; Undo brings both back, with the number.
- [ ] Duplicate a Group node, open one, remove an output: both Group nodes lose that output and its links.
- [ ] Inside a group, tap "Save as preset", name it, then open Presets: it is listed under "Groups", not under "Yours".
- [ ] Start a new empty graph, open Presets, tap the group preset: a Group node appears in view; "Enter group" shows the same inside as the one saved.
- [ ] Save a group preset, close and reopen the app, open Presets: the group preset is still under "Groups".

### Feedback digest: never used (S16, on the Pi)
- [ ] Copy `server.py` and `usecases.json` to the Pi and run `python3 server.py digest --hours 24`: a "Never used" part lists unopened rooms, untapped buttons per opened room, and unmoved sliders.
- [ ] Run the same with `--catalog /nonexistent.json`: "Never used" lists only rooms, with no error.
- [ ] With feedback on, choose Ramp, Wave and Random in Slit-scan and move Strength and Seed, then run `server.py evidence` the next night: n21slit-time shows as `seen`.
- [ ] In FFT, pick LowPass and drag Radius, then check the evidence: n21fft-update shows as `seen`.
- [ ] In Draw, Hide group, then Save, then check the evidence: n21draw-export shows as `seen`.

### Node Lab: Compose Stack (S06)
- [ ] Make two layers in Compose, then add a Compose Stack in Node Lab and link it to a Viewer: the backdrop shows the same picture as Compose.
- [ ] Patch something into Compose Layer 1 in the Rack so Compose gets a live layer; then link a Library Image node into the node's Layer 1: the Library picture replaces the live layer, at the same place and width.
- [ ] With no live layers in Compose, link a Library Image into Layer 3: the picture appears on top of the stack, fitted inside it.
- [ ] Set Unlinked to Hide on a node whose room has a Rack-fed live layer and nothing linked: that live layer is gone from the preview; the other layers stay.
- [ ] Change a layer's opacity in Compose, then go back to Node Lab: the preview shows the change.
- [ ] Link the node into Save to Library and tap Run: a full-size picture of the stack is saved in the Library.

### Draw: render size (S13)
- [ ] In Draw, tap "Size: Screen" next to Save: a menu with Screen, 2×, 3×, 4×, 4096 px and 6000 px, each with a pixel size; the canvas does not change size when it opens.
- [ ] Pick 2×, then tap Save: the message ends in a size twice the canvas, and the Library image has that size and sharp lines.
- [ ] On a tall phone canvas, open the menu: the 4× line shows a smaller size than 4× and says "4× is too big for one image"; saving at 4× works.
- [ ] Pick 4096 px, close the app, reopen Draw: the button still says "Size: 4096 px"; New canvas sets it back to Screen.
- [ ] Save on Screen: the message has no size and the image is exactly the canvas size, as before.
- [ ] Set a grating's blend to Subtract, pick 3×, then Save: it saves without a crash; the subtracted lines are sharp.

### MIDI in and out (S09)
- [ ] Plug in a USB MIDI keyboard, open the studio's back, go to "MIDI in" and tap "Find devices": the keyboard's name appears as a button.
- [ ] Switch MIDI in on, patch A into a Signal node, and move the keyboard's mod wheel: the status line shows "CC 1 = …" and the signal moves from 0 to 1.
- [ ] Patch MIDI in's Gate into a gate input, then hold a key and let it go: the gate is on while the key is held and off when you let go; D shows how hard the key was struck.
- [ ] On a device that takes MIDI, switch MIDI out on and patch a Sensors jack into A: the device gets CC 1 changes; the status line shows "CC 1 = …".
- [ ] With MIDI in on, press Home, wait, then come back: while away the device is closed and keys do nothing; back in the studio it reads keys again.
- [ ] On a phone with nothing plugged in, switch MIDI in on: the button turns off and says "no device to read from".

### Calculator (R02)
- [ ] Open Calculator, type "2 * (3 + 4)" and press Enter, then "ans + 1": 14, then 15; both lines show in History, newest on top.
- [ ] Type "100 °C in °F", then "5 km + 300 m": "212 °F", then "5.3 km".
- [ ] Type "255", press Enter, then "0xff & 0b1010 in bin": under 255 a row reads dec, hex, bin and oct; the second line gives "0b1010".
- [ ] Type "r = 2.5", then "r * 2", then tap the "r = 2.5" chip: the result is 5, and tapping the chip puts "r" in the field.
- [ ] Type "1.5", then "Send to a control…" → Develop · Exposure, then open Develop: a short message says it was set to 1.5; Develop's exposure shows 1.50 EV.
- [ ] Tap "Result jack: not patched" → Glitch lab · Seed, then type "42": the button shows the jack; Glitch's seed follows the result.

### Math room (R03)
- [ ] Open Math and tap each function chip: each shows its description and a curve; Linear is the diagonal line, Clock gate is four on/off steps.
- [ ] On Exponential, drag its first slider, then tap Reset under it: the curve and the picture change as you drag, and Reset brings them back.
- [ ] Pick a Library image, choose R, set Mix to 50 %, then turn Before on and off: only the red levels change, about halfway; Before shows the image as it was.
- [ ] Tap Save to Library, then open the Library: a new image appears as a child of the source image, and a "Saved … generation …" message shows.

### Rack: projector inputs and the Draw image layer (S07a)
- [ ] Rack: plug Camera "Last capture" into Draw "Image layer", then open Draw: a new image layer with the last capture; its readout says "live from the rack" and it updates after a new capture.
- [ ] With that layer on the canvas, press Save in Draw, then open the new file in the Library: the save has a "route.snapshot" image as a parent, and reopening its canvas shows that picture, not a grey box.
- [ ] Rack: try to plug Draw "Canvas" into Draw "Image layer": the cable is refused.
- [ ] Plug Compose "Composite" into Sequence "Projector", open Sequence, press "Projector: rack": the composite fills the screen and changes when Compose changes; tap closes it.
- [ ] Projector phone: plug Draw "Canvas" into Loop "Projector", connect both phones, set Next frame to "The rack's picture", start the loop: the first frame is the Draw canvas, and each capture's parent in the Library is a "route.snapshot" image.
- [ ] In the Library, press Use in Loop on any image while Loop "Projector" is plugged in: the cable is removed and the Loop room says it starts from that Library image.

### FM/AM nodes (R04)
- [ ] In Node Lab, add an FM/AM node (Time), link Value into a Drive Layer's Value, and start Live: the layer moves in a wobbling pattern; switching Mode between AM, FM and Ring changes how it moves.
- [ ] Add an FM/AM node with Min 0 and Max 8, without Live: its Value reads 4 (the middle).
- [ ] Link a Library Image into an FM/AM Image node (Filter), then into the Viewer: dark and light bands run down the picture; Amount 0 removes them.
- [ ] On the same node, set Effect to Shift and Direction to Across: the columns slide up and down in a wave, wrapping round the edges.
- [ ] Link a Clock's Phase into the FM/AM Image node's Phase and start Live: the bands scroll across the picture.
- [ ] Mute the FM/AM Image node: the picture shows unchanged.

### Landscape layouts and Sequence slits (S11)
- [ ] Put three or more items with different durations on the Sequence timeline; in Slit-scan pick Frames "Sequence", then switch between "One per item" and "By timing": a picture made of slits from the timeline; "By timing" gives more frames, and longer items take up more slits.
- [ ] In the UI Editor, turn the phone sideways, move a panel, then turn it back: the toolbar says "Landscape front", then "Portrait front"; the moved panel moves only in landscape; cables are the same in both; "Like portrait" puts landscape back.
- [ ] Open the studio's back (Node Lab, Studio routing), pick a module with knobs, and tap a knob: the exact-value box opens over the knob panel, not as a full-screen dialog; tapping outside it closes it.
- [ ] Open Library in landscape, then open one image: the grid's filter and sort bars are in a side panel and the grid uses the full height; the detail view has the image on the left and its details in the side panel.
- [ ] Open Node Lab in landscape: the graph uses the full height, and the toolbar is in a side panel whose edge can be dragged.
- [ ] Open Studio in landscape: the avatar, the project line and the tabs are in a rail on the left, and the tab's page fills the rest of the window.

### Lab notebook: notes and Rerun (R08)
- [ ] Studio, Notebook: tap "Add note" on an entry, type a line, Save; close and reopen the app: the note shows under the entry and the button now says "Edit note".
- [ ] Edit note, delete all the text, Save: the note is gone and the button says "Add note" again.
- [ ] Save a Draw canvas, then in Notebook tap "Rerun" on it: "Running…", then a message; a new top entry the same size reads "Run 2 · first run <time>", and the first now reads "First run · 1 rerun".
- [ ] Rerun a Glitch save and open both in the Library: both pictures look the same (same seed and settings).
- [ ] Tap Rerun on the rerun itself: the newest entry reads "Run 3 · first run <time of the first run>".
- [ ] Look at a Sequence GIF or camera capture in Notebook: it has "Add note" but no "Rerun" button.

### Main-thread work moved off the UI (S02)
- [ ] Open Camera, wait for the preview, switch to Library and back to Camera five times quickly: the app never freezes or shows "not responding", and the preview comes back each time.
- [ ] In Camera, tap between the Back and Front camera chips several times: each camera's preview appears; no "Camera error" message.
- [ ] Open Camera and leave for another room within half a second, five times; then open Camera and wait: the preview starts normally and no "Camera error" or "camera in use" message appears.
- [ ] In Library with many photos, fling the grid down to the bottom and back up: scrolling stays smooth; tiles you have seen show at once on the way back.
- [ ] In Library, tap "+" until the grid shows 2 across, then "−" back to 3: tiles reload at the new size and none shows another image's picture.
- [ ] In Library, open an image with parents and children, then tap a Parents or Children chip: the new image appears; the previous one never shows in its place.
- [ ] Open a Develop result in Library, turn on "A/B vs parent" at 30 ms for 20 seconds, then go back: the picture flips steadily without blank frames, and the app stays responsive.
- [ ] In Develop → LUT, import a 33-point .cube file, then add a Compose layer that uses that look: the screen stays responsive during the import; the look appears in Develop and on the Compose layer.
- [ ] In Develop → Presets, tap "Save current look as preset" three times quickly, leave Develop and come back: all three presets are listed.

### Physics room with dyn4j (R05)
- [ ] In Draw, draw a closed circle, a closed square, an open line across the lower half and add a picture; open Physics and press "Take from Draw": chips read Ball, Box, Line (Fixed) and Box; outlines sit on each shape.
- [ ] Press Run with gravity on Down: the ball and boxes fall, land on the line or the bottom edge and settle; the readout counts hits.
- [ ] Pick Tilt, press Run, tip the phone left and right, then lay it flat: bodies slide towards the low edge; lying flat they stay where they are.
- [ ] On the Studio back, patch Physics Hit into an input that shows a value or gate (for example Node Lab Signal 1), then run: the input jumps to 1 for an instant on each hit and back to 0; a resting body does not keep firing.
- [ ] Turn on Record, run for 2 seconds, press To Animate; then press Bake to Animate: Animate opens with the recorded frames after the current one, then with the baked frames; one Undo removes each batch.
- [ ] While Run is on, switch to another app for 10 seconds and come back: bodies are where they were when you left, then go on.
- [ ] Set Frame rate to 60 and Bake length to 20 s: the note says the bake stops early; the button reads "Bake 600 frames to Animate"; after baking the message says it stopped early.
- [ ] Record while running at 60 fps for over 10 s: the chip turns to "Record (600, full)" and switches off, and one message says Record is full.

### Nothing runs while the studio is closed (S01)
- [ ] Turn on the Sensors unit with the microphone allowed, then press Home for a minute: the microphone indicator goes off and no sensor jacks update; back in the studio the readings move again.
- [ ] Turn on OSC in, press Home, then send OSC to port 9000 from a laptop: nothing arrives while the studio is away; after you come back, the "last" line updates again.
- [ ] Link the two phones in Loop, press Home on one, then open the studio again: the link drops and says it was closed while off screen; on return it waits or connects again by itself.
- [ ] Turn on MIDI in with a controller attached, press Home, then come back: the device shows as closed while away and opens again on return.
- [ ] Leave the studio open in Patch live with sensors on, lock the screen for 30 minutes: Battery settings show almost no studio CPU time and no drain while the screen was off.

### Glitch: pixel sort and colours from a picture (R09)
- [ ] Set Pixel sort to Off, then turn "Sort angle" on the Glitch rack panel: Pixel sort switches to Angle and the lines follow the knob.
- [ ] In Glitch, tap "Another image…" and pick a strongly coloured Library image: the first image you see already has the new colours, with no flash of the old ones.
- [ ] Patch glitch.image into another module, pick a colour image, then turn a Glitch knob several times: the routed image keeps the new colours and updates as quickly as it does without a colour image.
- [ ] Save a glitch that uses "Colours from", then run that Notebook entry again: the rerun has the same colours as the first save.

### Patch jacks and rack presets (S07b)
- [ ] Flip to the back, open the Patch tab, tap LFO 1 and patch it into Node Lab · Signal 1: the Patch bay's rear panel lists LFO 1, LFO 2, Touch X, Touch Y and Route 1 to 4; the cable appears under All cables, and a Module Signal node follows the LFO.
- [ ] Add a route in Patch, start Live, and patch Patch bay · Route 1 into OSC out · A (OSC out on): OSC out sends a value from 0 to 1 that moves with the route.
- [ ] Make two cables, open All cables, tap "+ Save cables…", name it, then pull one cable and tap your preset: it asks first, then brings both cables back; Undo brings back the one-cable rack.
- [ ] Under All cables tap "Camera chain": Camera feeds Develop, Develop feeds Glitch and Glitch feeds Sequence; the chip is ticked and has no delete button.
- [ ] Save a rack preset, close and reopen the studio: your preset is still listed before the built-in ones.

### 3D low-poly room (R06)
- [ ] Open 3D, drag across the picture, then pinch: the view turns around the striped cube and floor, and pinching zooms in and out.
- [ ] In Shape, set Opacity to 50 %: the back faces' lines show through the front ones and moiré bands appear.
- [ ] In Save, tap "Save SVG" and open the file from Download/Studio: it looks the same as the view, with clean vector lines.

### Phone-to-phone sync (S17)
- [ ] Link both phones in Loop. On phone A: Library, open a picture, Send to, Other phone: both phones show a toast; the picture is in B's Library with the same tags, and "Reopen recipe" works.
- [ ] With the phones linked, tap Loop, Send, This project on A: B says "Got project …"; the project is listed in B's Studio, File, and opens.
- [ ] Send a large project and press Home on A halfway, then come back and send again: the Loop room says "Sending paused"; the percentage picks up near where it stopped, not at 0 %.

### Compose: per-layer Develop, handles, fonts (S15)
- [ ] Pick a photo layer, Adjust → Curve, drag a point; then LUT → Import .cube: only that layer changes, and Save matches the screen.
- [ ] Move tool: drag a corner, a side handle, then the round knob: even scale, one-axis stretch, turn; Undo reverts each drag.
- [ ] Add Text, pick Serif, Italic, Centre, then Script on the layer: the text redraws in each font, still centred where it was.

### Compose: healing and clone brush (S15b, 2026-10-26)
- [ ] `s15b-clone-paint-only` Select a non-paint layer (image or solid) and look at the toolbar. → Clone and Heal chips are not shown.
- [ ] `s15b-clone-source` On a paint layer with some colour, select Clone, tap once on the canvas. → A pink crosshair appears at the tapped point.
- [ ] `s15b-clone-paint` With the source set, drag elsewhere on the same paint layer. → Pixels from the source area appear at the destination with soft edges.
- [ ] `s15b-clone-no-fringes` Clone from a transparent area onto an opaque white area. → No dark fringes or darkening at the edges.
- [ ] `s15b-heal-colour` Switch to Heal tool, drag on a differently-coloured area. → Copied texture matches the destination's surrounding colour.
- [ ] `s15b-clone-undo` After a clone stroke, tap Undo. → The clone stroke disappears in one step.
- [ ] `s15b-clone-source-reset` Set clone source, then tap a different layer and back. → Clone source crosshair is gone; you must set it again.

### Plugins: behaviour, parade scope, share (S08)
- [ ] Plugins room, RGB parade, set Scope in to Develop: three traces (red, green, blue) of the picture.
- [ ] Forge: slider X, Give it a behaviour, add an LFO step, add output Level fed by the LFO, Save: Plugins room shows a moving Level bar.
- [ ] UI Editor: cable from "Level (out)" to a slider: the slider follows Level.
- [ ] Studio → Plugins, Share… on an installed plugin: the share sheet opens with a .studiomodule file.
- [ ] With an LFO plugin on, leave the app and come back: Level moves again, with no errors.

## Animate
- [ ] + Library frames: pick images, and they should become frames in that order.
- [ ] Grab / Replace / Edit in Draw, plus hold counts, onion skin and Play at different fps.
- [ ] Rec drag: drag a layer and frames should be captured as you go. The step helper should add N
      shifted frames.
- [ ] Barrier view: drag the slit sideways, then Save. You should get the interleaved image and the
      barrier in Library.

## Sequence + projector
- [ ] Frame shapes (Screen, First item, 1:1, 4:3, 3:4, 16:9, 9:16) and, per item, Fit / Fill / 1:1 and
      Turn 90°. The preview and the projector should match, with no cropping on the projector.
- [ ] Preview plays the right frames, including after you edit items. (It used to go black.)
- [ ] Projector on the S9+, and at 60 / 90 Hz on the S21. When you close it, the report should show
      few or no dropped vsyncs. (120 Hz on the S21 is already confirmed.)
- [ ] Export a GIF and an MP4. Both should appear in Library and in Gallery / Movies.

## Bridge
- [ ] Send to Samsung Gallery editor → edit → save → the inbox finds it → import. It should become a
      child of what you sent.
- [ ] The same round trip with Samsung Notes.
- [ ] Share an image into the studio from another app. It should be imported, and if you just sent
      to that app, it should become a child of the send.
- [ ] Screen recording: approve the consent dialog yourself, record, stop. It should be in Library and
      Movies/Studio.

## Camera
- [ ] Manual shutter, ISO, focus, white balance and torch. On the S9+, also aperture f/1.5 vs f/2.4.
- [ ] Burst with each stack mode (additive, mean, interlace). The result should look like the mode.
- [ ] RAW on: a DNG should be saved as a child of the JPEG.
- [ ] Preview overlay of a grating, the Draw canvas and the barrier. It should not show up in the
      photo.
- [ ] Library → Open in Camera should restore the settings.
- [ ] FV-5 button: FV-5 opens, and its photos reach the Bridge inbox.

### Bridge: folder watching (S18, 2026-10-26)
- [ ] `s18-fv5-capture` Open Bridge, launch FV-5 from it, take a photo, return to the studio. → The new image appears in the inbox within a few seconds without pulling to refresh.
- [ ] `s18-screenshot-detect` Open Bridge, take a screenshot (power + volume), return to the studio. → The screenshot appears in the inbox shortly after returning.
- [ ] `s18-background-stop` Open Bridge, switch to another app for 10 seconds, return. → No crash; debug log shows "folder observers stopped" then "folder observers started".
- [ ] `s18-no-crash-missing-folder` On a phone without Pictures/FV5, open Bridge. → No crash; observers start for the folders that exist.

## Loop (two phones)
- [ ] Link the phones (type one phone's address on the other). Synced mode: the projector phone shows
      a frame, the camera phone captures it, and the capture comes back as the next frame.
- [ ] Free-run mode.
- [ ] Torch strobe on the camera phone.
- [ ] Sequence as the source (next frame = the next item).
- [ ] Open question: on 2026-10-14 at 16:30 a second Loop run started on its own. Was that you?

## Patch bay
- [ ] Routes from hardware sensors: rotation, accelerometer, magnetometer, light and proximity
      should move the Draw layer.
- [ ] Microphone level (grant the permission yourself).
- [ ] Touch pad: drag on the live canvas.
- [ ] Sensors from the other phone while linked in Loop (Patch live on both).
- [ ] Record to Animate while live. One Undo in Animate removes the take, and one Undo in Draw takes
      back the session.
- [ ] Open as graph: the routes should appear in routing-nodes as Sensor/LFO → Drive Layer. Press
      ▶ Live there and it should move the same way. Undo in routing-nodes should bring back the
      previous graph.

## Develop
- [ ] Tone curve: drag a point, tap to add one, long-press to remove one.
- [ ] Hue mixer bands, exposure, temperature/tint and levels.
- [ ] Masks: lasso, brush and spot colour, each with feather and invert.
- [ ] Import a .cube file and use the amount slider. Export look as .cube.
- [ ] Style mix with another image (for example a Samsung AI Style result from the Bridge inbox).
- [ ] Presets: save and apply. Hold "before" to compare. Save at full resolution.

## FFT lab
- [ ] Brushes (erase, keep, boost) on the spectrum. The result should change visibly.
- [ ] Low / high / band-pass filters, sizes 256 / 512 / 1024, and Phase from another image.
- [ ] Save: it should be a child of both images.

## Glitch lab
- [ ] Bytes per pixel, row width, stride, offset, channel order, chroma shift and fills.
- [ ] JPEG generations with corruption on: it should still open.
- [ ] Sweep wall: tap a variant to take it.
- [ ] Save a result (never tested), including from a big 12 MP photo, which used to crash.

## Compose
- [ ] New document: from a layer, the screen, or a typed size; white, black or transparent.
      Save with a transparent background: the PNG should keep its transparency.
- [ ] Layers: Library image, Draw canvas, generators (with their settings), paint, text, fill and
      gradient (linear and radial).
- [ ] Per layer: opacity, the 17 blend modes, clip to the layer below, blend-if (shadows, highlights,
      feather).
- [ ] Move / pinch / twist, and exact X / Y / scale / rotation, flip, rename, raise / lower,
      duplicate, delete.
- [ ] Tools: Select (rectangle), Lasso, Crop, Brush, Eraser, Mask, Eyedropper.
- [ ] Selection → mask (feathered), fill / erase the selection on a paint layer.
- [ ] Painted masks: hide / reveal, size, softness, invert.
- [ ] Each filter on a layer: blur, sharpen, noise, invert, desaturate, posterize, threshold, FFT
      pass, Glitch.
- [ ] Develop adjustments on a layer.
- [ ] Colour picker: swatches, HSV, opacity, exact values.
- [ ] Pinch-zoom the view; tap should pick the top layer.
- [ ] Save: it should be in Library, with every image used listed as a parent. Reopen recipe should
      bring the stack back.

## routing-nodes
Basics
- [ ] Long-press on empty space: the Add menu opens, search works, and the node is added there.
- [ ] Drag from an output to an input to link. Wrong types should be refused.
- [ ] Drag a linked input to pick the link up and drop it somewhere else.
- [ ] Drop a link on a node's body: it should connect to the best input.
- [ ] Drag nodes, pinch zoom, pan, and double-tap empty space to frame everything.
- [ ] Number fields: tap to type an exact value, drag sideways to scrub.
- [ ] ◎ on any node: the backdrop shows that node's result.
- [ ] Library Image node → Pick image….
- [ ] Mute, Collapse, Duplicate, Delete, Select all, Rename.
- [ ] Save to Library and Send to Compose (▶ Run): the image should arrive, and Reopen recipe should
      bring the graph back.
- [ ] Presets: load each built-in one, save your own, delete it.

Node Wrangler tools
- [ ] Lazy connect: drag from one node to another. (It was broken before; now fixed.)
- [ ] Lazy mix: drag from one picture node to another, and a Mix of the two should appear.
- [ ] Cut links: swipe across links to cut them.
- [ ] Add reroutes: swipe across links to put a dot on each. You should be able to move a dot, and it
      should take the colour of what flows through it.
- [ ] Box select.
- [ ] Align (row or column) and Swap A↔B on a Mix.
- [ ] Drop a lone node onto a link: it should be inserted in between.
- [ ] Blend for all on several selected Mix nodes.

Live and time nodes
- [ ] ▶ Live with each Live preset. The fps shows in the corner.
- [ ] A Sensor node with the phone tilted (Rotation: roll), and with smoothing.
- [ ] A Sensor node set to Phone: Other while the phones are linked in Loop.
- [ ] Sequence Frames + Frame at + Clock: it should play the Sequence items.
- [ ] Record to Animate: Arm, let it record, Stop. The frames should be in Animate.
- [ ] Sample & Hold with a Clock Tick.
- [ ] Leave the room: Live should stop. Starting Patch live should stop routing-nodes Live, and the
      other way round.

Frames and groups
- [ ] Frame the selection, then drag its label bar: the nodes should move with it. Drag the corner to
      resize. Rename it, change its Colour, Remove it (the nodes should stay).
- [ ] Group the selection: a Group node should appear with sockets for the links that crossed. The
      result should stay the same.
- [ ] ⇥ / Enter group: Group Input / Output should be there. Link from Group Input's + to add an input.
      Exit group, Rename group.
- [ ] Type a value on the Group node's field: it should change the inside.
- [ ] Add a second copy of the group from Add menu → Group. Edit inside one copy: both should change.
- [ ] Ungroup: the nodes should come back, with the same result.
- [ ] Undo after each of these.

## Studio routing (Node Lab → ⟲ Studio routing)
- [ ] The whole app turns round; the room bar runs right to left. Each module tab shows its back.
- [ ] Compose's back: "Layer 1 ⟵ Draw · Canvas". A live layer should appear in Compose, and drawing in Draw
      should change it.
- [ ] All cables: drag from an output jack to an input jack; drag a plugged input away to unplug. Cables
      should hang and be coloured by type.
- [ ] Knobs: drag up / down, tap for an exact value; the front of the module follows.
- [ ] Module presets: save a module's state, change it, tap the preset; Undo in the module brings it back.
- [ ] Flip to front from a module's back: you land on that module's front.
- [ ] Patch Compose · Composite → Develop · Source. Develop should develop the composite live; Save should file
      the input first and then the result as its child.
- [ ] Try a loop (Develop · Developed → Compose · Layer 2 while Compose feeds Develop). It should be refused
      (greyed "would loop").
- [ ] Camera · Overlay ⟵ Draw · Canvas: the drawing should show over the camera preview.
- [ ] Node Lab: a Module In node (In 1) with Draw patched into Node Lab · In 1 should follow the drawing.
- [ ] Undo / Redo / Unplug all in the Rack; the patching should still be there after restarting the app.

## Studio module
- [ ] File: New project, open another, Save a copy as, Rename, Delete (not the open one), Export .zip
      (Download/Studio), Import .zip.
- [ ] Preferences: start room, interface size, keep screen on, haptics.
- [ ] Theme: every theme; light themes (Gruvbox light, Solarized light, Catppuccin Latte) stay readable.
- [ ] Profile: name, handle, about; saved, and inside an exported .zip.

## Develop deep analysis
- [ ] Scope chip: histogram → vectorscope → 3D height (drag to turn) → deep analysis card expanding over the
      canvas.
- [ ] Probe, Line, Region, Clipping, Waveform / RGB parade, 3D (drag, pinch, height slider); ✕ Close.

## Since the 2026-10-17 requests
- [ ] Redo in every room.
- [ ] Compose: hold layers to pick several; Group / Ungroup / Take out of group; hold and drag to reorder.
- [ ] Draw: hold object chips to pick several; Group / Ungroup; hold and drag to reorder.
- [ ] Brush: the stroke should show while you paint; Straight line for brush, eraser, mask and the Draw pen.
- [ ] Compose selection: Copy / Cut / Paste (a clipped layer, ready to move).
- [ ] Camera zoom: pinch the preview; the Zoom slider.
- [ ] Animate → Save GIF.
- [ ] Sequence: Projector brightness "Phone setting" vs "Set here".
- [ ] Glitch: Correction %, Correct at 1/N, Every N gens.
- [ ] Develop: Scope (histogram, vectorscope); Mask tab (blending, mask layers, combine, luminance); Style strip.
- [ ] Library: stars / flags / colour labels (one and many), sort, filters, pinch the grid, loupe swipe and
      zoom, Compare with two picked.

## Two-phone nodes (both phones need the 2026-10-16 22:00 build)
- [ ] Link the phones in Loop. On one, load "Two phones: stream a live moiré to the other phone" and
      press ▶ Live. The status should show "sent N".
- [ ] On the other, load "Two phones: project what the other phone sends". The backdrop should show the
      stream, and the status should show "received N".
- [ ] Projector node → ▶ Full screen, with and without Live. It should fill the whole screen at full
      brightness; tap or Back closes it.
- [ ] Send to Other Phone → ▶ Send once, without Live. A message should say "Sent W×H", and the other
      phone should show it.
- [ ] During a Loop run, From Other Phone on the projector phone should show each new capture.

## Two phones in general
- [ ] Both phones keep their own projects, with nothing synced between them. Is that OK for now?

---

## Build of 2026-10-21: everything below is new, nothing in it has been tried by hand

Each item: what to do → what you should see. The same checks are in the checklist page (studio-log.html), where you can mark them and write notes.

### Camera: overlay burnt into the photo
The overlay used to be preview only. Now it is composited into the saved photo.

- [ ] Camera → Overlay → Grating (opacity 60 %), Burn into capture on (it is by default). Take a photo and open it in the Library. → The grating lines are in the saved photo, lined up as on the preview.
- [ ] Switch Burn into capture off and shoot again. → The photo has no overlay. Its info (Library → capture) still names the overlay.
- [ ] Blend → Multiply, shoot; then Difference, shoot; then Screen. → Each photo shows the overlay blended that way. The live preview stays plain opacity (the line under the chips says so).
- [ ] Opacity 20 %, then 100 %, shoot both. → Faint and full overlay in the photos.
- [ ] Overlay → Draw with a few layers on the Draw canvas, burn, shoot. → The Draw layers are in the photo, in the same place as on the preview.
- [ ] Hide a Draw group (see the Draw checks), then shoot with Overlay → Draw. → The hidden group is not in the photo.
- [ ] Library → Use in → Camera on an image, burn, shoot. Then look at the photo's parents in the Library. → The image is in the photo, fitted and centred; the Library image is listed as a parent.
- [ ] Overlay → Barrier (from Animate), burn, shoot. → The barrier strips are in the photo.
- [ ] Burst of 4 with Additive stacking and a grating overlay, burn. → The overlay is in the stacked photo once, not four times over.
- [ ] Half resolution on, burn, shoot. → The overlay is still lined up (scaled to the smaller photo).
- [ ] Studio routing: cable something into Camera's Overlay jack, burn, shoot. → That image is in the photo too.
- [ ] Library → Open in Camera on a burnt capture. → Overlay, blend, opacity, spacing and angle come back; the burn switch matches the capture.
- [ ] Loop: shoot with an overlay set. → The Loop capture has no overlay burnt in (known gap).

### Draw: groups, history, A/B
Groups now nest and fold; the layer tabs branch downwards.

- [ ] Draw: pick two layers (hold the second), Group. → A tab ▾ Group 1 (2) appears and a row of its two layers opens below it.
- [ ] Tap the group tab. → It folds (▸) and its row goes; tap again to open.
- [ ] Hold the group tab. → Every layer in the group is picked.
- [ ] Open the group, pick two layers inside it, Group. → A subgroup tab appears in the group's row and its own row opens below that: the tabs branch downwards.
- [ ] Pick two groups (hold both tabs), Group. → Both become subgroups of a new group.
- [ ] Pick a layer in a group, Hide group (the Groups row). → All its layers disappear from the canvas, tabs show ◌, and a tap or lasso cannot pick them.
- [ ] Hide all, then Show all. → Every grouped layer hides, then shows (also layers hidden one by one inside groups).
- [ ] Rename on a group. → The tab shows the new name.
- [ ] Pick a group, Duplicate. → The copies sit exactly on the originals (no offset), as a new group of the same shape.
- [ ] Copy a group, Paste. → The copy lands a little to the side, as before.
- [ ] Ungroup on layers in a subgroup, then in a top-level group. → They leave one level up; an emptied group disappears.
- [ ] Group, hide, duplicate, then Undo three times. → Each step is undone in turn.
- [ ] Open a project saved before this build that had groups. → Old groups show as Group 1, Group 2 and so on, and work.
- [ ] Hide a group and Save the canvas to the Library. → The saved picture does not show the hidden group. Animate → Grab from Draw leaves it out too.
- [ ] Draw → History (next to Redo) after a few edits. → A list of steps, newest first, the current one marked. Tap an older one: the canvas goes back to it. Undone steps are greyed; a new edit drops them.
- [ ] Draw: Snap B, change the canvas, then hold the canvas still in the top right quarter. → A | B split, A on the left. Drag sideways: the line moves. Let go: it vanishes.
- [ ] Hold the bottom left, bottom right and top left quarters in turn. → Bottom left: A over B (drag up and down). Bottom right: B in full. Top left: the input view (Draw says it has none).
- [ ] Draw with the pen and move a layer with normal quick touches. → Nothing changes: only a still hold (half a second) starts A/B.

### Sliders: the two-zone thumb
Every slider in the studio has a red and blue thumb now.

- [ ] Drag the bottom (blue) half of a thumb along a slider. → The value follows across the whole range.
- [ ] Tap the bare track away from the thumb. → The value jumps there.
- [ ] Tap the right side of the top (red) half, then the left side. → One fine step up, then one down. The value is tidy (0.37, not 0.3700000001).
- [ ] Check the step on three sliders: 0..1 (Opacity), 0..360 (an angle) and 2..80 px (Spacing). → Roughly 0.01, 1 and 0.1 per step: a hundredth to a thousandth of the range.
- [ ] Hold the red half for two seconds. → After 0.4 s it repeats; each second the steps get ten times bigger.
- [ ] Drag sideways on the red half. → One step per 8 dp dragged, both ways.
- [ ] A whole-number slider (Burst, Strip, Rays). → It never lands between whole numbers.
- [ ] Step past either end. → The value stops at the end.
- [ ] In a room with a scrolling panel, scroll by dragging the panel, not the thumb. → The panel scrolls; the sliders do not move.
- [ ] Tap the number next to a slider and type an exact value. → Works as before.

### Studio → Config, user files, debug terminal
The Profile tab is now Config: profile, every flag, developer options, math parameters, file locations.

- [ ] Studio → Config. → Profile fields, Studio flags, Developer, Math parameters, Where things are saved.
- [ ] Change the interface size, haptics and your name. Close the app completely and open it again. → All three are kept.
- [ ] After installing this build over the old one: theme, name, project and the room you were in. → All kept (they were moved from the old settings into the new files).
- [ ] Look at Where things are saved, then find user.workspace with a file manager or over USB (Android/data/dk.cbavf.studio/files/). → user.workspace is there, readable text. user.profile is in the app's private folder (needs adb run-as).
- [ ] Studio → File: make a new project, switch to it, switch back. → Both open with their own Library. The open project is remembered after a restart.
- [ ] Export .zip, then open the zip on a computer. → It contains user.profile (your profile and flags), not user.workspace.
- [ ] Bridge → screen recording, then look in the open project's folder. → The recording is filed in the project you have open, not always in 'default'.
- [ ] Config → Math parameters: set slider.holdMs to 1000 and try the red half. → The hold now takes a second to start repeating. Reset to default restores 400.
- [ ] Type 'repeat' in the Math parameters search. → Only matching parameters remain.
- [ ] Config → Developer → Off, Show the debug terminal. → The terminal opens over the room, says output is off, and has no input field or keyboard.
- [ ] Level 1, then 2, then 3: change room, take a photo, use a gesture. → Level 1 shows errors only; 2 adds room changes, captures and gestures; 3 adds the camera's overlay line.
- [ ] Clear, then Close. → The lines go; Close hides the terminal; the level stays.
- [ ] Open Camera with camera access off in quick settings. → At level 1 an error line appears (red).

### Develop: channel mixer, and the 3D tool's channels
RGBA, CMYK and Lab.

- [ ] Develop → Mixer → Channels. → RGBA, CMYK and Lab chips, a gain and an offset slider for each channel. Hue bands is still there.
- [ ] RGBA: set R gain to 0 on a colour photo. → The red is gone from the picture.
- [ ] CMYK: K offset up, then C gain down. → The picture darkens, then loses cyan.
- [ ] Lab: L gain to 1.5, then a offset up. → Brighter, then more red-magenta.
- [ ] Change colour space. → The settings start over (the channels mean something else).
- [ ] Reset. → Back to neutral; the summary no longer says 'channels'.
- [ ] Save a Develop result with a channel mix, then Library → Open in Develop. → The mix comes back.
- [ ] Save the look as a preset, load it on another image. → The channel mix is part of the look.
- [ ] Develop → scope chip until Deep analysis → 3D. Pick Lab → L, then a, then b. → The height map changes with each; Luma is the old brightness view.
- [ ] 3D → RGBA → R on a photo with a red object. → The red object stands highest.
- [ ] 3D → CMYK → K. → Dark areas stand highest.

### UI Editor
The whole UI on one canvas. Front is the layout, back is the jack routing.

- [ ] Room bar → UI Editor. → Panels for every module, each with its controls (sliders, toggles, buttons).
- [ ] Pinch and drag the empty canvas. Fit. → Zoom and pan; Fit shows all of it.
- [ ] Drag a control; drag a panel's header; drag a control's bottom right corner. → Control moves (snapped), panel moves with its controls, control resizes.
- [ ] Undo and Redo after moving. → Each move is one step.
- [ ] ⟲ Back. → The canvas turns over (narrows and widens): the same layout mirrored left to right, every control now a jack.
- [ ] Compare a control's place on the front with its jack on the back. → Exactly mirrored across the canvas.
- [ ] On the back, drag from one jack to another, on any two modules. → A cable follows your finger and stays when you let go on a jack.
- [ ] Drag a second cable between the same two jacks; and from a jack to itself. → Refused (no duplicate, no self).
- [ ] Tap a jack, then Unplug. → Its cables go.
- [ ] Move some controls and patch cables, close the app, open the UI Editor again. → Layout and cables are as you left them.
- [ ] Select a Grating, then a Burst in Draw, then look in the UI Editor. → Both layer types' knobs are there (remembered).
- [ ] Patch a cable and use the studio. → Nothing changes in the rooms (cables are not wired to the controls yet).

### Plugins and the Module Forge
Data only: a module's face as a .studiomodule file.

- [ ] Studio → Plugins. → 'RGB parade' listed as built in, with a switch. 'No installed plugins yet.'
- [ ] Module Forge: name 'Test pad', kind Controller, add a slider 'X', a toggle 'Hold' and a button 'Go'. Save and switch on. → A toast says so.
- [ ] Open the UI Editor. → A 'Test pad' panel with the three controls; on the back they are jacks.
- [ ] Studio → Plugins → switch Test pad off. → Its panel is gone from the UI Editor.
- [ ] Export .studiomodule (in the Forge or in Plugins). → A file called test-pad.studiomodule is in Pictures/Studio.
- [ ] Remove Test pad, then Install .studiomodule… and pick that file. → It is back in the list; switch it on.
- [ ] Install something that is not a plugin (a photo, or a zip of other files). → 'That is not a plugin file'.
- [ ] Forge with an empty name. → The save and export buttons are off.

### A/B compare in Develop, and the rack

- [ ] Develop: Snap B on one look, change the look, hold the top right quarter of the image. → Vertical split, developed (A) on the left, B on the right.
- [ ] Hold the top left quarter. → The source image, labelled 'Library image' (or 'routed source').
- [ ] Studio routing: an 'A/B compare' unit with jacks A and B. Cable an image into B. → Holding the canvas then shows that image as B, instead of the snapshot.
- [ ] Config → A/B compare: hold with two fingers; then hold one finger. → Now it takes two fingers to start; one finger does nothing special.
- [ ] Develop: the old 'Hold: before' button. → Still works.
- [ ] Hold with no B at all. → 'No B yet: Snap B, or patch a cable into B'.

### Gestures and History in every room

- [ ] Two-finger tap in Compose, Develop, Draw, Nodes, FFT. → Undo in that room.
- [ ] Three-finger tap. → Redo.
- [ ] Three-finger swipe left, then right. → Next room, then back.
- [ ] Four-finger tap. → A list of the gestures.
- [ ] Swipe in from the right edge in Compose, FFT, Glitch, Node Lab, the UI Editor. → That room's History list; tap a step to jump.
- [ ] The same swipe in Animate, Sequence or Develop. → A message that the room has no history list yet (known gap).
- [ ] Pinch and twist in Draw. → Works as before; it does not undo.
- [ ] Tap one finger on everything. → Nothing new happens: one-finger touches are never taken.

### Slit-scan
Every slit taken from a different Animate frame.

- [ ] Slit-scan room with fewer than two Animate frames. → A message to make frames in Animate first.
- [ ] With 4+ frames: Vertical slits, width 3 px, Time None. → A picture of interleaved strips, like the barrier view.
- [ ] Horizontal. → The same, in rows.
- [ ] Slit width 1, then 20. → Fine, then coarse strips.
- [ ] Time → Ramp, Wave, Random (with Strength and Seed). → The picture bends through time; the same settings give the same picture.
- [ ] Save to Library. → A new image in the Library, opens in other rooms.
- [ ] 720, 1080 px. → Bigger picture, slower.

### Sensors in Node Lab

- [ ] Add a Sensor node. → Five outputs: Value, Raw, High-pass, Change, Seed.
- [ ] Live, Source = Accel X: hold the phone still, then shake it. → High-pass sits near 0 and swings when shaken; Change spikes.
- [ ] Seed into a Glitch node's seed (or any number input), tilt the phone. → The seed changes as the readings change.
- [ ] Open a graph saved before this build that has a Sensor node. → It loads, links to Value and Raw intact.

### This page
Feedback fields.

- [ ] Type in a note under any check. → It stays (this browser, or your account on claude.ai) and the ✎ button gets a dot.
- [ ] Type a room feedback and the overall feedback. → Saved the same way.
- [ ] Changelog tab: type feedback on an entry. → Saved.
- [ ] Copy all feedback. → Your broken checks, notes and feedback are on the clipboard as text.

### FFT (added after your first report)
The controls were checked on the S9+ without touching your screen (chip, painting and Save all worked on a fresh start), so if something still fails, the debug log line below tells what.

- [ ] FFT: pick LowPass, then drag the Radius slider. → The top line says ' · updating…' while the picture is being recalculated, then the spectrum changes (a LowPass blacks out the outer ring).
- [ ] Tap and drag on the spectrum with the Erase brush. → Red rings appear (with their mirror through the centre), Undo lights up, and the spectrum darkens where you painted.
- [ ] Press Result, then Magnitude again. → Result shows the filtered image; tapping it goes back to painting on the spectrum.
- [ ] Save, then Library → Reopen recipe on that FFT save. → FFT opens on the Result view, showing the saved picture, with the same filter and paint still there.
- [ ] If it feels slow or stuck: Studio → Config → Developer → level 3, Show the debug terminal, then paint once. → A line 'fft: rendered 512x512 … in N ms' shows how long each recalculation took. Note N (and the size) in the note.
- [ ] FFT: Phase from → Another image…, pick one, Save. Open the Library, find that save, Reopen recipe. → FFT opens on Result with the same source, the same phase image ('phase swap' in the top line), filter and paint. Not just the original picture.

### Live cables into FFT, Develop and Glitch (found on your phone)
A saved cable Compose → FFT made FFT show the Compose composite and ignore every image you picked or loaded, with nothing on screen saying so.

- [ ] With a cable into FFT, Develop or Glitch (Studio routing): open that room. → The top line says 'Live from Compose · Composite' (or whatever feeds it) and an 'Unplug · live from …' button sits beside Image….
- [ ] Press Unplug. → The cable goes (also in Studio routing) and the room shows the image you picked, or asks you to pick one.
- [ ] With a cable plugged in, pick an image with Image…, or Library → Use in → FFT / Develop / Glitch. → The cable is unplugged for you, and the picked image is what you see.
- [ ] With a cable plugged in, Library → Reopen recipe on an FFT, Glitch or Develop save. → The saved edit opens (the cable is unplugged), not whatever the cable carries.

## Build of 2026-10-23 (not tried by hand yet)
- Compose: put a layer over another with Mode Subtract and ReverseSubtract; the lower picture loses the upper one's brightness (never below black); opacity fades the effect; Save gives the same picture as the screen.
- Camera: overlay blend Subtract, take a photo, open it in Library: the overlay is subtracted in the saved file.
- Node Lab: a Mix node with Blend Subtract.
- Draw: the Blend row does not list Subtract (by design).
- Bridge: "Photo editor…" and "Share…" in Library open Android's chooser; "Open the gallery app" opens the phone's gallery.
- Icon: after the first launch the launcher shows the new icon with more of the picture.
- Layout: rotate the phone in Draw, Compose, Develop, FFT and Glitch. In landscape the controls sit in a panel at the right (drag its edge to resize; it keeps the size after a restart); in portrait they sit under the picture. Tap a slider's number: the box opens inside the controls panel, not over the picture.
- Node Lab gates: add Gate Switch (set State to On), Envelope (give it Attack, Decay, Sustain, Release) and a Viewer or any value input; link Gate Switch to Envelope's Gate input, press Live: the Level rises over the Attack, settles at Sustain, and falls over the Release when you set the switch back to Off. Try Trigger to Gate with a Clock's Tick, and Gate Divide set to 2.
- Envelope editor: in Node Lab add an Envelope node, select it, tap "Envelope…": drag the four handles (attack top, decay end, sustain end up and down, release end); the sliders below follow and typing a number in one moves its handle; pick a Curve and the drawing changes.
- Control envelopes: long-press a slider's name in Draw or Compose, choose "Add envelope…", shape it, set Depth, choose Gate button. Hold the "G" by the slider: the slider moves with the envelope and returns to your value when you let go. Choose Clock: it repeats by itself. "Remove" takes it off. Restart the app: the envelope is still there.
- Gate jacks: flip to the Studio's back: switch Gate A on and off, change the Clock rate. In "All cables" patch Gate A into Node Lab Signal 1; in Node Lab add Module Signal (Signal 1) and an Envelope, link them, press Live and toggle Gate A: the Envelope's level follows. In the UI Editor's back a button cannot be cabled to a toggle; a toggle can drive a slider.
- UI Editor cables: in the UI Editor flip to the back, cable Studio "Gate A" (toggle) to Studio "Interface size" (slider). Flip to the Studio's back and switch Gate A: the Interface size knob jumps between its ends. Remove the cable: it stops. Restart the app: the cable is still there and works.
- Channels: Node Lab: Split Channel (set Space and Channel) into a viewer shows one grey plane; Split four times into Merge Channels gives the picture back (try RGBA, CMYK, Lab). Studio back, Channels tab: patch a source in and a channel out into Node Lab In 1. Develop, deep analysis, 3D: "3 channels" shows three tinted meshes.
- A/B in more rooms: in Compose, FFT, Glitch and Animate press "Snap B", change the picture, then hold still on it for about half a second: the four quadrants show the input, a vertical split, a horizontal split, and B in full.
- History lists: in Animate, Sequence and Develop make a few edits, tap "History": the list shows Start, Edit 1, Edit 2 ... with the current one marked; tap an earlier one to jump back, a later one to go forward.
- Fringe predictor: in Draw add two gratings with different spacing, tap "Fringes": cyan lines show the predicted beat; move a Spacing slider and the lines follow; it matches the readout (beat spacing and angle).
- Sensor bus: Studio back, Sensors tab: "Sensors on", tilt and shake the phone and patch Roll or Shake into a Node Lab Signal input; Record a few seconds, stop, switch Replay on: the jacks carry the take in a loop. Patch Sensors Seed into Glitch Seed: the corruption follows the world.
- Slit-scan sources: Slit-scan room, Frames: "Library images", "Pick images…" and tap four pictures, Add: the slits are taken from them. "Rack stream": patch a picture (for example the Camera's last capture or the Channels unit) into Slit-scan's Stream jack on the Studio's back, press "Collect" and let the source change: frames count up, the scan builds from them.
- Palette: Studio, Config, switch on "Command palette in the notification shade" (allow notifications if asked). Pull down the shade: "Moiré Studio · <room>" with the project; tap Draw, Camera, Develop to jump; tap "Go to…", type a word like "node" and send. Add the "Moiré Studio" tile from the Quick Settings editor and tap it.
- Plugins room: Module Forge: build a Controller with two sliders and a toggle, "Save and switch on"; open the Plugins room: the controls work; in the UI Editor's back cable the plugin's toggle to a slider in another module. Loop: set the role and the host, restart the app: both are remembered (now kept in user.profile).
- Math parameters: Studio, Config: new groups sensor, envelope, gate, layout, ab, fringe; change gate.duty and the Studio clock gate's on-time changes; change layout.panelDefaultDp and a landscape panel without a remembered size changes.
- Quality of life: drag the red half of a slider and feel a tick per step; after a crash (or a kill) a banner says so on the next start; Camera, "Last in Library"; Animate shows little pictures for the frames; Library: pick several, Delete; open an image with children, "Delete with N children…".
- Draw subtract: Draw, select a grating, Blend row: Subtract (scroll the chips): the grating's ink is taken from what is below; ReverseSubtract the other way round.
- Favourite apps: Bridge room, "Favourite apps…", tick two apps: "Open <app>" buttons appear; untick: gone; restart: kept.
- Wide windows: rotate to landscape in Studio, Bridge, Module Forge, Plugins: the page is a centred column, not full-width lines.
- Creative extras: Studio, File, "Pitch test sheet (A4, SVG)": a file appears in Download/Studio; print at 100 %. Studio, Notebook: recent work, tap one. Develop, deep analysis, Line, "Hear this line": a sound that rises with the brightness along the line. Studio back, OSC in: switch on and send an OSC float to port 9000 (address /a to /d): the jack follows.
- Channel Mix: Node Lab, Channel Mix between a picture and a viewer: lower Gain 1 (red in RGBA) and the picture loses red; raise Offset 3 and it gains blue; set Space to CMYK and Offset 4 (K) up: the picture darkens.
- Feedback (build of 2026-10-24): fresh install, open the studio: the "Help improve the studio?" question appears once (No thanks keeps everything off). Studio → Config → Feedback: switch "Send optional diagnostic data" on, then "See what would be sent": a readable report with rooms and times, no file names. Visit two rooms, press Home, then "Send now": status says Sent (needs the Pi's HTTPS address set up). "Write a note…" sends your text. Switch off: waiting reports vanish. "Delete my data…": the server forgets this phone.
