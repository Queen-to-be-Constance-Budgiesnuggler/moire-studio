# Feedback to the developer (diagnostics and bug reports)

Built 2026-10-24. Off by default. When a person switches it on, the studio sends small usage and error reports to a collector
on `deb-server-1`, and a nightly job pulls a digest into `docs/FEEDBACK-DIGEST.md`.

## What the person sees
- First launch of the studio edition: a question, "Help improve the studio?" with **Basic**, **Full** or **No thanks**. Asked once.
- Studio → Config → **Feedback to the developer**: a switch "Send optional diagnostic data", the Basic/Full choice, "Send only on Wi-Fi",
  **See what would be sent** (the real report, laid out to read), **Send now**, **Write a note…**, **New random id**, **Delete my data…**.

## Levels
| Level | Sent |
|---|---|
| Off | Nothing is recorded, queued or sent. |
| Basic | Time and visits per room, how often each control is used (a drag counts once), errors (scrubbed), crashes (exception and 14 stack frames), app version, phone model and Android version. |
| Full | Basic plus the last 300 debug lines (scrubbed) and the studio settings (flags, maths parameters). Full keeps the debug log at the info level while it is on. |

Never sent: pictures, projects, file names, text typed into the studio, name, handle, about, Loop host, favourite apps, location, contacts.
A **note** is sent only when the person presses Send, at any level, with the version and phone model and nothing else.
`Redactor` removes links, email addresses, file paths and IP addresses from errors, crash text and log lines.
`ReportBuilder` is the one place that decides what leaves the phone (unit-tested: Off builds nothing, Basic has no log or settings).

## How it works
- App, package `dk.cbavf.studio.feedback`: `FeedbackModel.kt` (levels, report shape, `Redactor`, `SessionRecorder`, `ReportBuilder`),
  `FeedbackQueue.kt` (bounded outbox in `filesDir/feedback/outbox`: 40 files or 2 MB), `Feedback.kt` (hooks, crash handler, upload),
  `FeedbackUi.kt` (dialog and Config section).
- Hooks: room changes (`StudioApp`), slider use (`LabeledSlider`), every `Debug.e/w` error, an uncaught-exception handler, and
  `ApplicationExitInfo` for "not responding" and "killed for memory" on the next start.
- A report is an increment: one is queued each time the studio goes to the background and every 10 minutes while it is open, then
  sent (gzip, `POST /v1/report`) when the phone is on an unmetered network (Wi-Fi), oldest first. A server refusal (4xx) drops the
  report; a network error keeps it for the next try.
- The id is a random UUID made when feedback is first switched on. **Delete my data** calls `POST /v1/forget` and clears the queue.
- The address and ingest key are not in git: set `feedback.url` and `feedback.key` in `local.properties`. The key only keeps strangers'
  noise out; it is not a secret once the app is installed.
- `network_security_config.xml` allows cleartext HTTP only to 127.0.0.1 and localhost (for a test server reached with `adb reverse`).

## The collector (`tools/feedback-server`)
One Python file, standard library only, SQLite. Runs on the Pi as the user service `moire-feedback` in `~/MoireFeedback`
listening on `127.0.0.1:8760`. Limits: 256 KB per post, 60 reports/hour per install, 300/hour
per address, 500 MB database. `python3 server.py`: `status`, `digest [--hours N]`, `notes`, `block ID`, `unblock ID`, `forget ID`,
`purge --days N`, `key`. 24 tests: `python -m unittest test_server` in `tools/feedback-server`.

**One step left for the person at the Pi** (sudo needs a password, so it was not done remotely):
`sudo tailscale funnel --bg --https=8443 http://127.0.0.1:8760`
This adds `https://deb-server-1.tailcfabf0.ts.net:8443`. Until then reports
wait in the phone's queue. Undo: `sudo tailscale funnel --https=8443 off`.

## Automation
`tools/agent/nightly.ps1` (Windows task `MoireStudio-Nightly`, 03:07) builds and tests, then runs `server.py digest --hours 24` over ssh and
writes `docs/FEEDBACK-DIGEST.md`: installs, sessions, time per room, most-used controls, logged errors, crashes grouped by signature
(app frames first), and notes. Read it at the start of a session.
A "Never used" part covers the last 7 days (`--unused-days N`): rooms no session opened, and in the rooms that were opened, the catalog's
controls never tapped and its sliders never moved. It uses only what reports already carry; the catalog (`--catalog FILE`, default
`usecases.json` next to `server.py`) names the controls, so without it only rooms are listed.

## Tried on 2026-10-24 (S9+, loopback test server)
First-run question, Full level, a session report (rooms and times, log lines, settings keys), a forced crash (`am crash`) reported with its
stack, and the digest. Not yet tried: the HTTPS address, "Delete my data" and "Write a note" on the phone, the Wi-Fi-only rule on mobile
data, a Basic report from a phone, the first-run question on the S21.

## Checklist tests done in daily use (added 2026-10-24)
Feedback also tells which of the 260 checklist tests a person has carried out, so some can be ticked off from normal use.

- **Catalog:** `app/src/main/assets/usecases.json` maps each test id to triggers: `tap:Room|Label` (a tap on a named control), `room:Room`, `ctl:key` (a slider).
  `tools/usecases/build_catalog.py` generates it from the checklist text and the labels in each room's Kotlin source (82 of 260 tests so far:
  56 strong, 26 weak; a label only counts if it sits on a chip, button, menu item, toggle or tab in the code); `tools/usecases/overrides.json` holds hand-made entries (17, checked against the source: sliders as `ctl:RoomEnum.Label`, real button
  texts instead of plain headings); `tools/usecases/REVIEW.md` lists what was generated. `CatalogSyncTest` checks that every trigger names a
  real room and that the collector's room list matches the studio's.
- **In the app:** `TapProbe` watches every tap without taking it, reads the text of the control under the finger from the accessibility tree
  (never from a text field) and `Feedback.tap` keeps it only if the catalog names that exact control. A project, layer or tag name therefore
  cannot be sent. Reports carry `taps: {"Draw|Group": 2}`.
- **On the server:** `server.py evidence` says, per test: `seen` (every trigger met by one install on one day), `touched` (a weak test: its
  triggers are met but they cover only part of the steps), `partial`, or `none`, with install count, last time, and whether errors or a crash
  were logged that day. Nightly, `nightly.ps1` copies the catalog to the Pi and writes `docs/AUTO-EVIDENCE.json`.
- **On the checklist page:** a badge under a test ("done in use ×1 · 24 Oct", dashed for "area used", red when errors or a crash were logged).
  `tools/usecases/evidence_batch.py` makes the writes for the page's `auto` store; a Claude session applies them (`ArtifactData` batch).
  The badge never sets the person's ✓ or ✕: it says the steps were done, not that the result looked right.
- **Limits:** a visual result ("the picture looks right"), a gesture on a canvas, a slider drag's exact value and anything not tied to a named
  control cannot be seen from taps. Roughly half the tests can get a badge; the rest stay manual.
- **Seen on the S9+ on 2026-10-24:** taps on Config and Plugins were recorded and turned into badges. Those two came from my own driving over adb.
- **S9+ name-resolution fault (found 2026-10-24):** reports from the S9+ stayed queued because host names did not resolve on it. Its Tailscale
  app (1.102.4) sets itself as the phone's DNS server (100.100.100.100 and its IPv6 twin) and that resolver times out on every query, even for
  its own MagicDNS name, while Tailscale carries IP traffic fine (the Pi answers by IP). The Wi-Fi's own resolver (193.162.153.164) and 1.1.1.1
  answer when asked directly, and DNS-over-HTTPS from the phone works. So it is the Tailscale app on that phone, not the network, the Pi or
  the server. Fix on the phone: switch Tailscale off and on (restarts its DNS responder), or turn off "Use Tailscale DNS settings" in the app
  so the phone uses the Wi-Fi's DNS (the funnel name then resolves publicly). Do not stop Tailscale remotely: adb to this phone runs over it.
  Testers outside the tailnet are not affected.
