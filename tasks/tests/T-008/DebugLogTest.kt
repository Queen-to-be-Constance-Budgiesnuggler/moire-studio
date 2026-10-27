package dk.cbavf.studio.debug

import org.junit.Assert.*
import org.junit.Test

class DebugLogTest {
    @Test fun levelZeroLogsNothing() {
        val d = DebugLog(level = 0)
        d.log(DebugLog.ERROR, "t", "x")
        assertEquals(0, d.size)
    }
    @Test fun levelFilterAndRuntimeChange() {
        val d = DebugLog(level = DebugLog.INFO)
        d.log(DebugLog.ERROR, "a", "e"); d.log(DebugLog.INFO, "a", "i"); d.log(DebugLog.TRACE, "a", "t")
        assertEquals(listOf("e", "i"), d.entries().map { it.msg })
        d.level = DebugLog.TRACE
        d.log(DebugLog.TRACE, "a", "t2")
        assertEquals(3, d.size)
        d.log(0, "a", "never")
        assertEquals(3, d.size)
    }
    @Test fun capacityDropsTheOldest() {
        val d = DebugLog(level = 3, capacity = 3)
        for (i in 1..5) d.log(1, "t", "m$i")
        assertEquals(listOf("m3", "m4", "m5"), d.entries().map { it.msg })
        assertEquals(3, d.size)
    }
    @Test fun lineFormat() {
        var now = 3_723_004L
        val d = DebugLog(level = 3, clock = { now })
        d.log(1, "cam", "boom")
        now = 86_400_000L + 45_296_789L
        d.log(2, "ui", "hello")
        d.log(3, "x", "trace")
        assertEquals(listOf("01:02:03.004 E/cam: boom", "12:34:56.789 I/ui: hello", "12:34:56.789 T/x: trace"), d.lines())
    }
    @Test fun clearEmpties() {
        val d = DebugLog(level = 3)
        d.log(1, "t", "m"); d.clear()
        assertEquals(0, d.size)
        assertTrue(d.lines().isEmpty())
    }
}
