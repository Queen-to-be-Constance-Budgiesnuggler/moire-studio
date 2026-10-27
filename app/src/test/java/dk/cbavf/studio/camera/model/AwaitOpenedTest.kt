package dk.cbavf.studio.camera.model

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AwaitOpenedTest {
    @Test fun whatIsDeliveredIsReturnedAndKept() = runBlocking {
        val released = mutableListOf<String>()
        val got = awaitOpened<String>({ released += it }) { deliver, _ -> deliver("camera") }
        assertEquals("camera", got)
        assertTrue(released.isEmpty())
    }

    @Test fun aFailureIsThrown() = runBlocking {
        try {
            awaitOpened<String>({}) { _, failWith -> failWith(IllegalStateException("Camera error 3")) }
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertEquals("Camera error 3", e.message)
        }
    }

    @Test fun somethingThatArrivesAfterCancellingIsReleased() = runBlocking {
        val released = mutableListOf<String>()
        var deliverLater: ((String) -> Unit)? = null
        val wait = async(start = CoroutineStart.UNDISPATCHED) {
            awaitOpened<String>({ released += it }) { deliver, _ -> deliverLater = deliver }
        }
        wait.cancel()
        yield()
        deliverLater!!("camera")
        assertEquals(listOf("camera"), released)
    }

    @Test fun aFailureAfterCancellingIsIgnored() = runBlocking {
        var failLater: ((Throwable) -> Unit)? = null
        val wait = async(start = CoroutineStart.UNDISPATCHED) {
            awaitOpened<String>({}) { _, failWith -> failLater = failWith }
        }
        wait.cancel()
        yield()
        failLater!!(IllegalStateException("late"))
        assertTrue(wait.isCancelled)
    }
}
