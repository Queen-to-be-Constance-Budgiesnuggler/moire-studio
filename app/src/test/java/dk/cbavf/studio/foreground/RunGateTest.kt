package dk.cbavf.studio.foreground

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunGateTest {
    /** Writes what it is told into [log]. */
    private class Probe(val name: String, val log: MutableList<String>) : RunGate.Hook {
        override fun onStop() { log += "$name stop" }
        override fun onStart() { log += "$name start" }
    }

    @Test fun aNewGateIsStarted() {
        assertTrue(RunGate().isStarted)
    }

    @Test fun stopClearsTheFlagAndStartSetsIt() {
        val g = RunGate()
        g.onStop()
        assertFalse(g.isStarted)
        assertFalse(g.started.value)
        g.onStart()
        assertTrue(g.isStarted)
    }

    @Test fun hooksAreToldOnStopAndOnStart() {
        val log = mutableListOf<String>()
        val g = RunGate()
        g.add(Probe("a", log))
        g.onStop()
        g.onStart()
        assertEquals(listOf("a stop", "a start"), log)
    }

    @Test fun aSecondStopOrStartDoesNothing() {
        val log = mutableListOf<String>()
        val g = RunGate()
        g.add(Probe("a", log))
        g.onStop()
        g.onStop()
        g.onStart()
        g.onStart()
        assertEquals(listOf("a stop", "a start"), log)
        assertEquals(1, g.stops)
    }

    @Test fun hooksStopLastAddedFirstAndStartInOrder() {
        val log = mutableListOf<String>()
        val g = RunGate()
        g.add(Probe("a", log))
        g.add(Probe("b", log))
        g.onStop()
        g.onStart()
        assertEquals(listOf("b stop", "a stop", "a start", "b start"), log)
    }

    @Test fun aHookAddedWhileStoppedIsToldAtOnce() {
        val log = mutableListOf<String>()
        val g = RunGate()
        g.onStop()
        g.add(Probe("a", log))
        assertEquals(listOf("a stop"), log)
    }

    @Test fun aRemovedHookIsNotToldAnyMore() {
        val log = mutableListOf<String>()
        val g = RunGate()
        val remove = g.add(Probe("a", log))
        remove()
        g.onStop()
        assertTrue(log.isEmpty())
    }

    @Test fun stopsAreCounted() {
        val g = RunGate()
        repeat(3) { g.onStop(); g.onStart() }
        assertEquals(3, g.stops)
    }

    @Test fun awaitStartedReturnsAtOnceWhileStarted() = runBlocking {
        assertEquals(Unit, withTimeoutOrNull(1_000) { RunGate().awaitStarted() })
    }

    @Test fun awaitStartedWaitsWhileStoppedAndGoesOnAtStart() = runBlocking {
        val g = RunGate()
        g.onStop()
        val waiting = async { g.awaitStarted(); "went on" }
        delay(50)
        assertFalse(waiting.isCompleted)
        g.onStart()
        assertEquals("went on", withTimeoutOrNull(1_000) { waiting.await() })
    }

    @Test fun sleepWaitsItsTimeWhileStarted() = runBlocking {
        val g = RunGate()
        val t0 = System.nanoTime()
        g.sleep(60)
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 55)
    }

    @Test fun sleepDoesNotEndWhileStopped() = runBlocking {
        val g = RunGate()
        g.onStop()
        assertNull(withTimeoutOrNull(150) { g.sleep(10) })
    }

    @Test fun aStopDuringSleepHoldsItUntilStart() = runBlocking {
        val g = RunGate()
        var woke = false
        val job = launch { g.sleep(100); woke = true }
        delay(20)
        g.onStop()
        // Well past the hundred milliseconds, but the studio is off screen.
        delay(200)
        assertFalse(woke)
        g.onStart()
        withTimeoutOrNull(1_000) { job.join() }
        assertTrue(woke)
    }

    @Test fun aPollingLoopDoesNotStepWhileStopped() = runBlocking {
        val g = RunGate()
        var steps = 0
        val job = launch { while (true) { g.sleep(5); steps++ } }
        delay(60)
        g.onStop()
        delay(20)
        val atStop = steps
        delay(150)
        assertEquals(atStop, steps)
        g.onStart()
        delay(60)
        job.cancel()
        assertTrue(steps > atStop)
    }

    @Test fun sleepOfZeroReturnsAtOnce() = runBlocking {
        assertEquals(Unit, withTimeoutOrNull(1_000) { RunGate().sleep(0) })
    }

    @Test fun aGateMadeStoppedStartsStopped() = runBlocking {
        val g = RunGate(onScreen = false)
        assertFalse(g.isStarted)
        yield()
        assertNull(withTimeoutOrNull(50) { g.awaitStarted() })
    }
}
