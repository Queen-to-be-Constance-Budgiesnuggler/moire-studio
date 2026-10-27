import gzip
import json
import os
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import uuid

os.environ["MOIRE_FEEDBACK_DATA"] = tempfile.mkdtemp()
import server  # noqa: E402

INSTALL = str(uuid.uuid4())


def post(port, path, obj, key=None, gz=False):
    raw = json.dumps(obj).encode()
    headers = {"Content-Type": "application/json", "X-Key": key if key is not None else server.ingest_key()}
    if gz:
        raw = gzip.compress(raw)
        headers["Content-Encoding"] = "gzip"
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=raw, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def session(**kw):
    base = {"kind": "session", "level": "basic", "install": INSTALL, "session": "s1",
            "app": {"version": "1.0"}, "device": {"model": "SM-G965F"}, "durationMs": 120000,
            "rooms": {"Draw": {"ms": 90000, "visits": 2}}, "controls": {"draw.opacity": 7},
            "errors": [{"tag": "Camera", "msg": "no access"}]}
    base.update(kw)
    return base


class ServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.httpd = server.ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        cls.port = cls.httpd.server_address[1]
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()

    def setUp(self):
        server.forget(INSTALL)
        server._hits.clear()

    def test_report_is_stored(self):
        code, body = post(self.port, "/v1/report", session())
        self.assertEqual(200, code)
        self.assertEqual(1, len(server.rows(1)))

    def test_gzip_body_works(self):
        self.assertEqual(200, post(self.port, "/v1/report", session(), gz=True)[0])

    def test_wrong_key_is_refused(self):
        self.assertEqual(401, post(self.port, "/v1/report", session(), key="nope")[0])
        self.assertEqual(0, len(server.rows(1)))

    def test_bad_install_id_and_kind_are_refused(self):
        self.assertEqual(400, post(self.port, "/v1/report", session(install="not-a-uuid"))[0])
        self.assertEqual(400, post(self.port, "/v1/report", session(kind="spy"))[0])

    def test_basic_level_drops_log_and_settings(self):
        post(self.port, "/v1/report", session(log=["secret line"], settings={"a": 1}))
        body = json.loads(server.rows(1)[0]["body"])
        self.assertNotIn("log", body)
        self.assertNotIn("settings", body)

    def test_full_level_keeps_log_but_trims_it(self):
        post(self.port, "/v1/report", session(level="full", log=["x" * 500] * 400))
        body = json.loads(server.rows(1)[0]["body"])
        self.assertEqual(300, len(body["log"]))
        self.assertEqual(300, len(body["log"][0]))

    def test_oversize_body_is_refused(self):
        self.assertEqual(413, post(self.port, "/v1/report", session(note="x" * 300000))[0])

    def test_per_install_rate_limit(self):
        codes = [post(self.port, "/v1/report", session())[0] for _ in range(server.MAX_PER_INSTALL_HOUR + 2)]
        self.assertEqual(200, codes[0])
        self.assertEqual(429, codes[-1])

    def test_blocked_install_is_refused(self):
        with server.connect() as c:
            c.execute("INSERT OR REPLACE INTO blocked VALUES(?,?,?)", (INSTALL, "t", 0))
        try:
            self.assertEqual(403, post(self.port, "/v1/report", session())[0])
        finally:
            with server.connect() as c:
                c.execute("DELETE FROM blocked WHERE install=?", (INSTALL,))

    def test_forget_deletes_everything_for_one_install(self):
        post(self.port, "/v1/report", session())
        post(self.port, "/v1/report", session(session="s2"))
        code, body = post(self.port, "/v1/forget", {"install": INSTALL})
        self.assertEqual((200, 2), (code, body["deleted"]))
        self.assertEqual(0, len(server.rows(1)))

    def test_crashes_group_by_signature(self):
        crash = {"exception": "java.lang.NullPointerException: x", "frames": ["a.b.C.d", "dk.cbavf.studio.draw.DrawRoom.f"]}
        post(self.port, "/v1/report", session(kind="crash", crash=crash))
        post(self.port, "/v1/report", session(kind="crash", crash=crash, session="s2"))
        sigs = {r["sig"] for r in server.rows(1, "crash")}
        self.assertEqual(1, len(sigs))
        self.assertIn("x2", server.digest(1))

    def test_digest_names_rooms_controls_errors_and_notes(self):
        post(self.port, "/v1/report", session())
        post(self.port, "/v1/report", session(kind="note", note="slider felt stuck", session="s3"))
        text = server.digest(1)
        for word in ("Draw 1 min", "draw.opacity x7", "Camera: no access", "slider felt stuck"):
            self.assertIn(word, text)

    def test_evidence_marks_a_test_seen_only_when_every_trigger_is_met_by_one_install(self):
        catalog = {"tests": {
            "t-one": {"all": ["tap:Draw|Group"]},
            "t-two": {"all": ["tap:Draw|Group", "tap:Draw|Undo", "room:Draw"]},
            "t-three": {"all": ["tap:Compose|Save"]},
            "t-ctl": {"all": ["ctl:draw.opacity"]},
        }}
        post(self.port, "/v1/report", session(taps={"Draw|Group": 2}))
        ev = server.evidence(catalog, 1)["tests"]
        self.assertEqual("seen", ev["t-one"]["state"])
        self.assertEqual(1, ev["t-one"]["installs"])
        self.assertEqual("partial", ev["t-two"]["state"])
        self.assertEqual(["tap:Draw|Undo"], ev["t-two"]["missing"])
        self.assertEqual("none", ev["t-three"]["state"])
        self.assertEqual("seen", ev["t-ctl"]["state"])
        server.forget(INSTALL)

    def test_a_weak_test_is_only_touched(self):
        catalog = {"tests": {"w": {"all": ["tap:Draw|Group"], "weak": True}}}
        post(self.port, "/v1/report", session(taps={"Draw|Group": 1}))
        self.assertEqual("touched", server.evidence(catalog, 1)["tests"]["w"]["state"])
        server.forget(INSTALL)

    def test_a_crash_forced_over_adb_is_not_counted_as_evidence_of_a_crash(self):
        catalog = {"tests": {"x": {"all": ["tap:Draw|Group"]}}}
        post(self.port, "/v1/report", session(taps={"Draw|Group": 1}))
        post(self.port, "/v1/report", session(kind="crash", session="s9", crash={"exception": "android.app.RemoteServiceException$CrashedByAdbException: shell-induced crash", "frames": []}))
        self.assertEqual(0, server.evidence(catalog, 1)["tests"]["x"]["crashes"])
        server.forget(INSTALL)

    def test_never_used_lists_the_rooms_no_session_opened(self):
        unused = server.never_used([{"rooms": {"Draw": {"ms": 5}}}, {"rooms": {"FFT": {"ms": 5}}}])
        self.assertNotIn("Draw", unused["rooms"])
        self.assertNotIn("FFT", unused["rooms"])
        self.assertIn("Library", unused["rooms"])
        self.assertEqual(len(server.ROOM_TITLES) - 2, len(unused["rooms"]))

    def test_never_used_lists_catalog_taps_never_seen_only_for_rooms_that_were_opened(self):
        catalog = {"tests": {"a": {"all": ["tap:Draw|Group", "tap:Draw|Undo"]}, "b": {"all": ["tap:Library|Compare"]}}}
        unused = server.never_used([{"rooms": {"Draw": {}}, "taps": {"Draw|Group": 1}}], catalog)
        self.assertEqual({"Draw": ["Undo"]}, unused["taps"])

    def test_never_used_lists_catalog_sliders_never_moved(self):
        catalog = {"tests": {"a": {"all": ["ctl:Fft.Radius", "ctl:SlitScan.Seed"]}}}
        unused = server.never_used([{"rooms": {"FFT": {}}, "controls": {"Fft.Radius": 3}}], catalog)
        self.assertEqual(["SlitScan.Seed"], unused["sliders"])

    def test_never_used_without_a_catalog_lists_rooms_only(self):
        unused = server.never_used([{"rooms": {"Draw": {}}}], None)
        self.assertEqual({}, unused["taps"])
        self.assertEqual([], unused["sliders"])

    def test_never_used_with_no_sessions_says_there_is_nothing_to_compare(self):
        text = "\n".join(server.never_used_lines(server.never_used([]), 0, 7))
        self.assertIn("nothing to compare", text)
        self.assertNotIn("Rooms never opened", text)

    def test_never_used_shortens_a_long_list_of_labels(self):
        labels = {"Draw": [f"L{i:02}" for i in range(server.UNUSED_LABELS_PER_ROOM + 3)]}
        text = "\n".join(server.never_used_lines({"rooms": [], "taps": labels, "sliders": []}, 1, 7))
        self.assertIn("and 3 more", text)

    def test_digest_names_what_was_never_used(self):
        catalog = {"tests": {"a": {"all": ["tap:Draw|Group", "tap:Draw|Hide group"]}}}
        post(self.port, "/v1/report", session(taps={"Draw|Group": 1}))
        text = server.digest(1, catalog)
        self.assertIn("## Never used", text)
        self.assertIn("Rooms never opened: Studio, Library", text)
        self.assertIn("- Draw: Hide group", text)

    def test_load_catalog_gives_none_for_a_missing_file(self):
        self.assertIsNone(server.load_catalog(os.path.join(tempfile.mkdtemp(), "nope.json")))

    def test_health_needs_no_key(self):
        with urllib.request.urlopen(f"http://127.0.0.1:{self.port}/v1/health") as r:
            self.assertEqual(200, r.status)


if __name__ == "__main__":
    unittest.main()
