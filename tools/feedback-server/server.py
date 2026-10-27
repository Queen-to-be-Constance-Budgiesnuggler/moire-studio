#!/usr/bin/env python3
"""Moire Studio feedback collector. Standard library only; runs on the Pi (deb-server-1).

Phones post session reports, crash reports and typed notes when the person has switched feedback on. Nothing here asks who the
person is: an install is a random id the app made, and the person can ask for everything under it to be deleted.

  python3 server.py serve [PORT]        listen on 127.0.0.1:PORT (default 8760); put the HTTPS front door in front of it
  python3 server.py status              counts
  python3 server.py digest [--hours N] [--catalog FILE] [--unused-days N]
                                        a markdown summary of the last N hours (default 24), with what was never used in the
                                        last N days (default 7; the catalog names the controls, default usecases.json)
  python3 server.py notes [--hours N]   typed notes, newest first
  python3 server.py evidence [--catalog FILE] [--days N]   JSON: which checklist tests were carried out (see usecases.json)
  python3 server.py block ID | unblock ID
  python3 server.py forget ID           delete everything held for one install
  python3 server.py purge --days N      delete reports older than N days (default 90)
  python3 server.py key                 print the ingest key (made on first run)
"""
import gzip
import hashlib
import json
import os
import re
import sqlite3
import sys
import threading
import time
import uuid
from collections import Counter, defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOME = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("MOIRE_FEEDBACK_DATA", os.path.join(HOME, "data"))
MAX_BODY = 256 * 1024
MAX_PER_INSTALL_HOUR = 60
MAX_PER_IP_HOUR = 300
MAX_DB_MB = 500
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
KINDS = {"session", "crash", "note"}
LEVELS = {"basic", "full"}
# Room titles as the app reports them; keep in step with enum Room in app/.../ui/StudioApp.kt (a unit test there checks it)
ROOM_TITLES = ("Studio", "Library", "Compose", "Node Lab", "Draw", "Animate", "Sequence", "Bridge", "Camera", "Loop", "Patch", "Develop",
               "FFT", "Glitch", "Slit-scan", "Calculator", "Math", "3D", "Physics", "Module Forge", "Plugins", "UI Editor")
UNUSED_LABELS_PER_ROOM = 15

_lock = threading.Lock()
_hits = defaultdict(list)  # rate limit: key -> timestamps


def db_path():
    os.makedirs(DATA, exist_ok=True)
    return os.path.join(DATA, "feedback.db")


def connect():
    c = sqlite3.connect(db_path(), timeout=10)
    c.execute("""CREATE TABLE IF NOT EXISTS reports(
        id INTEGER PRIMARY KEY AUTOINCREMENT, received REAL, install TEXT, session TEXT, kind TEXT, level TEXT,
        version TEXT, device TEXT, sig TEXT, body TEXT)""")
    c.execute("CREATE INDEX IF NOT EXISTS r_time ON reports(received)")
    c.execute("CREATE INDEX IF NOT EXISTS r_install ON reports(install)")
    c.execute("CREATE TABLE IF NOT EXISTS blocked(install TEXT PRIMARY KEY, why TEXT, at REAL)")
    return c


def ingest_key():
    os.makedirs(DATA, exist_ok=True)
    p = os.path.join(DATA, "key")
    if not os.path.exists(p):
        with open(p, "w") as f:
            f.write(uuid.uuid4().hex)
        os.chmod(p, 0o600)
    return open(p).read().strip()


def too_fast(key, limit):
    now = time.time()
    with _lock:
        hits = [t for t in _hits[key] if now - t < 3600]
        if len(hits) >= limit:
            _hits[key] = hits
            return True
        hits.append(now)
        _hits[key] = hits
    return False


def crash_signature(report):
    """Group crashes: the exception line plus the first frame in the app's own code."""
    err = report.get("crash") or {}
    first = str(err.get("exception", ""))[:200]
    frame = next((f for f in err.get("frames", []) if "dk.cbavf" in str(f)), "")
    return hashlib.sha1((first + "|" + str(frame)).encode()).hexdigest()[:10] if first else ""


def clean(report):
    """Return (ok, problem). Keeps only fields we know and trims them, so a hostile post cannot fill the disk."""
    if not isinstance(report, dict):
        return False, "not an object"
    if report.get("kind") not in KINDS:
        return False, "kind"
    if report.get("level") not in LEVELS:
        return False, "level"
    if not UUID_RE.match(str(report.get("install", ""))):
        return False, "install id"
    for k in ("note",):
        if k in report:
            report[k] = str(report[k])[:4000]
    if report["level"] == "basic":
        for k in ("log", "settings"):
            report.pop(k, None)  # basic never carries log lines or settings, even if a client sends them
    if isinstance(report.get("log"), list):
        report["log"] = [str(x)[:300] for x in report["log"][-300:]]
    return True, ""


def store(report):
    ok, why = clean(report)
    if not ok:
        return 400, why
    with connect() as c:
        if c.execute("SELECT 1 FROM blocked WHERE install=?", (report["install"],)).fetchone():
            return 403, "blocked"
        size = os.path.getsize(db_path()) if os.path.exists(db_path()) else 0
        if size > MAX_DB_MB * 1024 * 1024:
            return 507, "full"
        app = report.get("app") or {}
        dev = report.get("device") or {}
        c.execute(
            "INSERT INTO reports(received,install,session,kind,level,version,device,sig,body) VALUES(?,?,?,?,?,?,?,?,?)",
            (time.time(), report["install"], str(report.get("session", ""))[:64], report["kind"], report["level"],
             str(app.get("version", ""))[:32], str(dev.get("model", ""))[:48], crash_signature(report), json.dumps(report)))
    return 200, "ok"


def forget(install):
    with connect() as c:
        n = c.execute("DELETE FROM reports WHERE install=?", (install,)).rowcount
    return n


class Handler(BaseHTTPRequestHandler):
    server_version = "MoireFeedback"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    def send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_json(self):
        n = int(self.headers.get("Content-Length", "0") or 0)
        if n <= 0 or n > MAX_BODY:
            return None, 413
        raw = self.rfile.read(n)
        if self.headers.get("Content-Encoding") == "gzip":
            try:
                raw = gzip.decompress(raw)
            except Exception:
                return None, 400
            if len(raw) > MAX_BODY * 4:
                return None, 413
        try:
            return json.loads(raw), 200
        except Exception:
            return None, 400

    def ip(self):
        # behind the HTTPS front door the real address arrives in this header
        return (self.headers.get("X-Forwarded-For") or self.client_address[0]).split(",")[0].strip()

    def do_GET(self):
        if self.path == "/v1/health":
            return self.send(200, {"ok": True})
        self.send(404, {"error": "not found"})

    def do_POST(self):
        if self.headers.get("X-Key") != ingest_key():
            return self.send(401, {"error": "key"})
        if too_fast("ip:" + self.ip(), MAX_PER_IP_HOUR):
            return self.send(429, {"error": "slow down"})
        data, code = self.read_json()
        if data is None:
            return self.send(code, {"error": "body"})
        if self.path == "/v1/report":
            if too_fast("in:" + str(data.get("install", "")), MAX_PER_INSTALL_HOUR):
                return self.send(429, {"error": "slow down"})
            code, msg = store(data)
            return self.send(code, {"ok": code == 200, "msg": msg})
        if self.path == "/v1/forget":
            iid = str(data.get("install", ""))
            if not UUID_RE.match(iid):
                return self.send(400, {"error": "install id"})
            return self.send(200, {"ok": True, "deleted": forget(iid)})
        self.send(404, {"error": "not found"})


# ---------- command line ----------

def since(hours):
    return time.time() - hours * 3600


def rows(hours, kind=None):
    with connect() as c:
        q = "SELECT received,install,session,kind,level,version,device,sig,body FROM reports WHERE received>=?"
        a = [since(hours)]
        if kind:
            q += " AND kind=?"
            a.append(kind)
        return [dict(zip(("received", "install", "session", "kind", "level", "version", "device", "sig", "body"), r))
                for r in c.execute(q + " ORDER BY received DESC", a)]


def span(ms):
    """Short durations read as seconds, longer ones as minutes."""
    return f"{ms // 1000} s" if ms < 60000 else f"{ms // 60000} min"


def never_used(bodies, catalog=None):
    """What no session touched: rooms never opened, and the catalog's taps and sliders never used in rooms that were opened.
    Works from what reports already carry (rooms, taps, controls), so the phone sends nothing new for it."""
    rooms_seen, taps_seen, ctl_seen = set(), set(), set()
    for b in bodies:
        rooms_seen.update((b.get("rooms") or {}).keys())
        taps_seen.update(k for k, n in (b.get("taps") or {}).items() if int(n or 0) > 0)
        ctl_seen.update(k for k, n in (b.get("controls") or {}).items() if int(n or 0) > 0)
    rooms = [r for r in ROOM_TITLES if r not in rooms_seen]
    taps, sliders = defaultdict(set), set()
    for spec in ((catalog or {}).get("tests") or {}).values():
        for t in (spec or {}).get("all", []):
            kind, _, key = str(t).partition(":")
            if kind == "tap" and key not in taps_seen:
                room, _, label = key.partition("|")
                if room not in rooms:  # a room never opened is listed once, not control by control
                    taps[room].add(label)
            elif kind == "ctl" and key not in ctl_seen:
                sliders.add(key)
    return {"rooms": rooms, "taps": {r: sorted(ls) for r, ls in sorted(taps.items())}, "sliders": sorted(sliders)}


def never_used_lines(unused, sessions, days):
    out = ["## Never used", "", f"Last {days:g} days, {sessions} sessions."]
    if not sessions:
        return out + ["", "No sessions, so nothing to compare."]
    out += ["", "Rooms never opened: " + (", ".join(unused["rooms"]) or "none")]
    if unused["taps"]:
        out += ["", "Catalog controls never tapped:"]
        for room, labels in unused["taps"].items():
            more = len(labels) - UNUSED_LABELS_PER_ROOM
            out.append(f"- {room}: " + ", ".join(labels[:UNUSED_LABELS_PER_ROOM]) + (f", and {more} more" if more > 0 else ""))
    if unused["sliders"]:
        out += ["", "Catalog sliders never moved: " + ", ".join(unused["sliders"])]
    return out


def digest(hours, catalog=None, unused_days=7.0):
    rs = rows(hours)
    out = ["# Feedback digest", "", f"Last {hours} h, made {time.strftime('%Y-%m-%d %H:%M')}.", ""]
    if not rs:
        return "\n".join(out + ["No reports."])
    installs = {r["install"] for r in rs}
    sessions = [r for r in rs if r["kind"] == "session"]
    crashes = [r for r in rs if r["kind"] == "crash"]
    notes = [r for r in rs if r["kind"] == "note"]
    out += [f"- {len(installs)} installs, {len(sessions)} sessions, {len(crashes)} crashes, {len(notes)} notes",
            "- Builds: " + ", ".join(f"{v or '?'} x{n}" for v, n in Counter(r["version"] for r in rs).most_common(5)),
            "- Devices: " + ", ".join(f"{v or '?'} x{n}" for v, n in Counter(r["device"] for r in rs).most_common(5)), ""]
    rooms, controls, errors = Counter(), Counter(), Counter()
    total_ms = 0
    for r in sessions:
        b = json.loads(r["body"])
        for name, v in (b.get("rooms") or {}).items():
            rooms[name] += int((v or {}).get("ms", 0))
        for cid, n in (b.get("controls") or {}).items():
            controls[cid] += int(n)
        for e in b.get("errors") or []:
            errors[f"{e.get('tag', '?')}: {str(e.get('msg', ''))[:120]}"] += 1
        total_ms += int(b.get("durationMs", 0))
    out.append(f"## Use\n\nTotal time in the studio: {span(total_ms)}.\n")
    out.append("Rooms by time: " + (", ".join(f"{n} {span(ms)}" for n, ms in rooms.most_common(8)) or "none"))
    out.append("")
    out.append("Controls most used: " + (", ".join(f"{c} x{n}" for c, n in controls.most_common(10)) or "none"))
    # never used is judged over a longer window than the day: one quiet day says little
    days = max(unused_days, hours / 24)
    week = [json.loads(r["body"]) for r in rows(days * 24, "session")]
    out += [""] + never_used_lines(never_used(week, catalog), len(week), days)
    out += ["", "## Errors logged", ""]
    out += [f"- x{n} {e}" for e, n in errors.most_common(10)] or ["- none"]
    out += ["", "## Crashes", ""]
    groups = defaultdict(list)
    for r in crashes:
        groups[r["sig"] or "unknown"].append(r)
    for sig, rs2 in sorted(groups.items(), key=lambda kv: -len(kv[1])):
        b = json.loads(rs2[0]["body"])
        c = b.get("crash") or {}
        out.append(f"- x{len(rs2)} `{sig}` {str(c.get('exception', ''))[:160]} (build {rs2[0]['version']}, {rs2[0]['device']})")
        frames = c.get("frames") or []
        mine = [f for f in frames if "dk.cbavf" in str(f)]
        for f in (mine[:3] + [f for f in frames if f not in mine][:1]):
            out.append(f"    - {str(f)[:140]}")
    if not groups:
        out.append("- none")
    out += ["", "## Notes", ""]
    for r in notes[:20]:
        b = json.loads(r["body"])
        out.append(f"- {time.strftime('%m-%d %H:%M', time.localtime(r['received']))} [{r['install'][:8]}] {b.get('note', '')[:300]}")
    if not notes:
        out.append("- none")
    return "\n".join(out)


def satisfied(trigger, bucket):
    """Is one use-case trigger ('tap:Room|Label', 'room:Room', 'ctl:Key') met by what one install did in one day?"""
    kind, _, key = trigger.partition(":")
    if kind == "tap":
        return bucket["taps"].get(key, 0) > 0
    if kind == "room":
        return key in bucket["rooms"]
    if kind == "ctl":
        return bucket["ctl"].get(key, 0) > 0
    return False


def evidence(catalog, days=30):
    """Which checklist tests have been carried out, from what installs did. 'Seen' means every trigger of the test was met by one
    install on one day; it says the steps were done and nothing broke visibly, not that the result looked right."""
    tests = catalog.get("tests", {})
    buckets = defaultdict(lambda: {"taps": Counter(), "rooms": set(), "ctl": Counter(), "errors": 0, "crashes": 0, "last": 0.0})
    for r in rows(days * 24):
        b = json.loads(r["body"])
        day = time.strftime("%Y-%m-%d", time.gmtime(r["received"]))
        bk = buckets[(r["install"], day)]
        bk["last"] = max(bk["last"], r["received"])
        for k, n in (b.get("taps") or {}).items():
            bk["taps"][k] += int(n)
        for name in (b.get("rooms") or {}):
            bk["rooms"].add(name)
        for k, n in (b.get("controls") or {}).items():
            bk["ctl"][k] += int(n)
        bk["errors"] += len(b.get("errors") or [])
        # a crash forced over adb (am crash, a test) is not one the person had
        if r["kind"] == "crash" and "CrashedByAdbException" not in r["body"]:
            bk["crashes"] += 1
    out = {}
    for tid, spec in tests.items():
        need = spec.get("all", [])
        seen_by, best, errors, crashes, last = set(), [], 0, 0, 0.0
        for (install, _day), bk in buckets.items():
            have = [t for t in need if satisfied(t, bk)]
            if len(have) > len(best):
                best = have
            if need and len(have) == len(need):
                seen_by.add(install)
                errors += bk["errors"]
                crashes += bk["crashes"]
                last = max(last, bk["last"])
        # a weak test names only part of its steps, so meeting its triggers says the area was used, not that the test was carried out
        state = ("touched" if spec.get("weak") else "seen") if seen_by else ("partial" if best else "none")
        out[tid] = {"state": state, "installs": len(seen_by), "have": best, "missing": [t for t in need if t not in best],
                    "errors": errors, "crashes": crashes, "last": last}
    return {"generated": time.time(), "days": days, "tests": out}


def load_catalog(path):
    """The use-case catalog, or None when it is missing or damaged (the digest then lists rooms only)."""
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def main(argv):
    cmd = argv[1] if len(argv) > 1 else ""
    hours = 24.0
    if "--hours" in argv:
        hours = float(argv[argv.index("--hours") + 1])
    if cmd == "serve":
        port = int(argv[2]) if len(argv) > 2 and argv[2].isdigit() else 8760
        ingest_key()
        connect().close()
        print(f"listening on 127.0.0.1:{port}", flush=True)
        ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
    elif cmd == "status":
        with connect() as c:
            n = c.execute("SELECT COUNT(*), COUNT(DISTINCT install) FROM reports").fetchone()
            b = c.execute("SELECT COUNT(*) FROM blocked").fetchone()[0]
        print(f"{n[0]} reports from {n[1]} installs, {b} blocked, db {os.path.getsize(db_path()) // 1024} KB")
    elif cmd == "digest":
        path = argv[argv.index("--catalog") + 1] if "--catalog" in argv else os.path.join(HOME, "usecases.json")
        unused_days = float(argv[argv.index("--unused-days") + 1]) if "--unused-days" in argv else 7.0
        print(digest(hours, load_catalog(path), unused_days))
    elif cmd == "evidence":
        path = argv[argv.index("--catalog") + 1] if "--catalog" in argv else os.path.join(HOME, "usecases.json")
        days = int(argv[argv.index("--days") + 1]) if "--days" in argv else 30
        print(json.dumps(evidence(json.load(open(path, encoding="utf-8")), days), indent=1))
    elif cmd == "notes":
        for r in rows(hours, "note"):
            print(time.strftime("%Y-%m-%d %H:%M", time.localtime(r["received"])), r["install"][:8], json.loads(r["body"]).get("note", ""))
    elif cmd in ("block", "unblock") and len(argv) > 2:
        with connect() as c:
            if cmd == "block":
                c.execute("INSERT OR REPLACE INTO blocked VALUES(?,?,?)", (argv[2], "manual", time.time()))
            else:
                c.execute("DELETE FROM blocked WHERE install=?", (argv[2],))
        print("ok")
    elif cmd == "forget" and len(argv) > 2:
        print("deleted", forget(argv[2]))
    elif cmd == "purge":
        days = int(argv[argv.index("--days") + 1]) if "--days" in argv else 90
        with connect() as c:
            n = c.execute("DELETE FROM reports WHERE received<?", (time.time() - days * 86400,)).rowcount
        print("deleted", n)
    elif cmd == "key":
        print(ingest_key())
    else:
        print(__doc__)


if __name__ == "__main__":
    main(sys.argv)
