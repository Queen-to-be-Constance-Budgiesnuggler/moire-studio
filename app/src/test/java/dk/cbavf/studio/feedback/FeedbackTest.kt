package dk.cbavf.studio.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FeedbackTest {
    private var now = 1_000L
    private fun recorder() = SessionRecorder("s1", { now })
    private val app = AppInfo("0.1.0", 1, "studio")
    private val dev = DeviceInfo("SM-G965F", 33)
    private val install = "123e4567-e89b-42d3-a456-426614174000"

    @Test fun roomTimeAddsUpAndVisitsAreCounted() {
        val r = recorder()
        r.enterRoom("Draw"); now += 5_000
        r.enterRoom("Compose"); now += 2_000
        r.enterRoom("Draw"); now += 1_000
        val s = r.stats()
        assertEquals(RoomUse(6_000, 2), s.rooms["Draw"])
        assertEquals(RoomUse(2_000, 1), s.rooms["Compose"])
        assertEquals(8_000, s.durationMs)
    }

    @Test fun backgroundTimeIsNotCounted() {
        val r = recorder()
        r.enterRoom("Draw"); now += 4_000
        r.pause(); now += 60_000
        r.resume(); now += 1_000
        val s = r.stats()
        assertEquals(5_000, s.rooms["Draw"]!!.ms)
        assertEquals(5_000, s.durationMs)
    }

    @Test fun aDragCountsAsOneUseOfAControl() {
        val r = recorder()
        repeat(30) { r.control("draw.Opacity"); now += 20 }
        assertEquals(1, r.stats().controls["draw.Opacity"])
        now += 3_000
        r.control("draw.Opacity")
        assertEquals(2, r.stats().controls["draw.Opacity"])
    }

    @Test fun controlIdsAreCapped() {
        val r = recorder()
        repeat(SessionRecorder.MAX_CONTROLS + 50) { r.control("c$it"); now += 2_000 }
        assertEquals(SessionRecorder.MAX_CONTROLS, r.stats().controls.size)
    }

    @Test fun repeatedIdenticalErrorsAreKeptOnceAndTheListIsBounded() {
        val r = recorder()
        repeat(5) { r.error("Camera", "no access") }
        assertEquals(1, r.stats().errors.size)
        repeat(80) { r.error("T", "e$it") }
        assertEquals(SessionRecorder.MAX_ERRORS, r.stats().errors.size)
    }

    @Test fun errorsAreScrubbedOfPathsLinksAndAddresses() {
        val r = recorder()
        r.error("Library", "cannot open /storage/emulated/0/Pictures/Studio/me.png for a@b.com at https://x.example/p?q=1 from 100.64.1.2")
        val msg = r.stats().errors.single().msg
        assertFalse(msg.contains("Pictures"))
        assertFalse(msg.contains("a@b.com"))
        assertFalse(msg.contains("example"))
        assertFalse(msg.contains("100.64"))
        assertTrue(msg.contains("<path>") && msg.contains("<email>") && msg.contains("<link>") && msg.contains("<ip>"))
    }

    @Test fun offBuildsNothing() {
        val s = recorder().apply { enterRoom("Draw") }.stats()
        assertNull(ReportBuilder.session(FeedbackLevel.Off, install, app, dev, s, listOf("l"), mapOf("a" to "1")))
        assertNull(ReportBuilder.crash(FeedbackLevel.Off, install, app, dev, "s", 0, "E", emptyList(), "", emptyList()))
    }

    @Test fun basicNeverCarriesLogLinesOrSettings() {
        val s = recorder().apply { enterRoom("Draw") }.stats()
        val r = ReportBuilder.session(FeedbackLevel.Basic, install, app, dev, s, listOf("secret"), mapOf("name" to "x"))!!
        assertNull(r.log); assertNull(r.settings)
        assertFalse(FeedbackJson.encode(r).contains("secret"))
    }

    @Test fun fullCarriesTheLastThreeHundredLogLinesScrubbed() {
        val s = recorder().apply { enterRoom("Draw") }.stats()
        val log = List(400) { "line $it /storage/emulated/0/x.png" }
        val r = ReportBuilder.session(FeedbackLevel.Full, install, app, dev, s, log, mapOf("haptics" to "true"))!!
        assertEquals(300, r.log!!.size)
        assertTrue(r.log!!.first().startsWith("line 100"))
        assertFalse(r.log!!.any { it.contains("emulated") })
        assertEquals("true", r.settings!!["haptics"])
    }

    @Test fun aNoteGoesWhateverTheLevelButOnlyWithText() {
        assertNotNull(ReportBuilder.note(install, app, dev, "s", 5, "the slider stuck"))
        assertNull(ReportBuilder.note(install, app, dev, "s", 5, "   "))
        val n = ReportBuilder.note(install, app, dev, "s", 5, "hi")!!
        assertEquals("note", n.kind); assertNull(n.log); assertTrue(n.rooms.isEmpty())
    }

    @Test fun crashFramesAreTrimmedAndScrubbed() {
        val frames = List(30) { "dk.cbavf.studio.X.f$it(/data/user/0/dk.cbavf.studio/X.kt:$it)" }
        val r = ReportBuilder.crash(FeedbackLevel.Basic, install, app, dev, "s", 9, "java.lang.IllegalStateException: boom", frames, "crash", emptyList())!!
        assertEquals(14, r.crash!!.frames.size)
        assertTrue(r.crash!!.frames.all { !it.contains("/data/") })
    }

    @Test fun reportsRoundTripThroughJson() {
        val s = recorder().apply { enterRoom("Draw"); now += 1000; control("draw.Opacity"); error("T", "m") }.stats()
        val r = ReportBuilder.session(FeedbackLevel.Basic, install, app, dev, s, emptyList(), emptyMap())!!
        assertEquals(r, FeedbackJson.decode(FeedbackJson.encode(r)))
        assertTrue(FeedbackJson.encode(r).contains("\"kind\":\"session\""))
    }

    @Test fun queueKeepsOrderAndDropsTheOldestPastTheLimit() {
        val dir = Files.createTempDirectory("fbq").toFile()
        val q = FeedbackQueue(dir, maxFiles = 3)
        repeat(5) { q.add("{\"n\":$it}"); Thread.sleep(2) }
        val left = q.pending().map { it.readText() }
        assertEquals(listOf("{\"n\":2}", "{\"n\":3}", "{\"n\":4}"), left)
        q.clear()
        assertTrue(q.pending().isEmpty())
    }

    @Test fun queueDropsOldestWhenTooBig() {
        val dir = Files.createTempDirectory("fbq").toFile()
        val q = FeedbackQueue(dir, maxFiles = 40, maxBytes = 100)
        repeat(4) { q.add("x".repeat(60)); Thread.sleep(2) }
        assertEquals(1, q.pending().size)
    }

    @Test fun unknownWireLevelMeansOff() {
        assertEquals(FeedbackLevel.Off, FeedbackLevel.of("bogus"))
        assertEquals(FeedbackLevel.Full, FeedbackLevel.of("full"))
    }
}

class UseCaseCatalogTest {
    private val json = """{"version":1,"tests":{
        "a":{"all":["tap:Draw|Group","room:Draw"]},
        "b":{"all":["tap:Studio|Config"],"weak":true},
        "c":{"all":["ctl:draw.opacity"]}}}"""

    @Test fun onlyTapTriggersBecomeTheAllowlist() {
        assertEquals(setOf("Draw|Group", "Studio|Config"), UseCaseCatalog.parse(json).taps)
    }

    @Test fun aTapOutsideTheCatalogIsNeverKept() {
        val c = UseCaseCatalog.parse(json)
        assertEquals("Draw|Group", c.keyFor("Draw", "  Group "))
        assertNull(c.keyFor("Draw", "My secret layer name"))
        assertNull(c.keyFor("Compose", "Group"))
    }

    @Test fun damagedOrMissingCatalogMeansNothingIsRecorded() {
        assertTrue(UseCaseCatalog.parse(null).taps.isEmpty())
        assertTrue(UseCaseCatalog.parse("{not json").taps.isEmpty())
        assertTrue(UseCaseCatalog.parse("""{"tests":5}""").taps.isEmpty())
    }

    @Test fun tapsAreCountedOncePerSecondNotOncePerEvent() {
        var now = 0L
        val r = SessionRecorder("s", { now })
        repeat(3) { r.tap("Draw|Group"); now += 100 }
        now += 1_000
        r.tap("Draw|Group")
        assertEquals(2, r.stats().taps["Draw|Group"])
    }
}
