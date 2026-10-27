package dk.cbavf.studio.link

import dk.cbavf.studio.camera.model.CaptureInfo
import dk.cbavf.studio.camera.model.StackMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkTest {
    @Test(timeout = 20_000)
    fun messagesAndPayloadsCrossTheSocket() = runBlocking {
        val server = Link.listen(port = 0)
        val accepted = async { Link.accept(server) }
        val client = Link.connect("127.0.0.1", server.localPort)
        val projector = accepted.await()

        client.send(LinkMessage.Hello("SM-G965F", LoopRole.Camera))
        assertEquals(LinkMessage.Hello("SM-G965F", LoopRole.Camera) to null, projector.receive().let { it.first to it.second })

        projector.send(LinkMessage.Start(LoopMode.Synced, 2000, strobe = true))
        projector.send(LinkMessage.Show(3, "a1"))
        assertEquals(LinkMessage.Start(LoopMode.Synced, 2000, true), client.receive().first)
        val (show, none) = client.receive()
        assertEquals(LinkMessage.Show(3, "a1"), show)
        assertNull(none)

        // A capture: header plus a JPEG-sized payload.
        val jpeg = ByteArray(3_000_000) { (it * 31).toByte() }
        val info = CaptureInfo("0", exposureNs = 1_000_000, iso = 100, burst = 2, stack = StackMode.Mean, projectedAssetId = "a1")
        // Read while sending: a payload larger than the socket buffers only goes through if someone reads.
        val received = async(Dispatchers.IO) { projector.receive() }
        client.send(LinkMessage.Captured(3, info, 3024, 4032), jpeg)
        val (captured, payload) = received.await()
        assertEquals(LinkMessage.Captured(3, info, 3024, 4032), captured)
        assertArrayEquals(jpeg, payload)

        client.close()
        projector.close()
        server.close()
    }
}
