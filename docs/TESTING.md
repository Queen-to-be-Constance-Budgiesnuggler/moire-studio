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
