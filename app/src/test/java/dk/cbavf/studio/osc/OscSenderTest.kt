package dk.cbavf.studio.osc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class OscSenderTest {
    @Test fun aMessageArrivesAtTheReceiverAndParsesBack() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 2000
            OscSender(InetAddress.getLoopbackAddress(), receiver.localPort).use { sender ->
                assertTrue(sender.send("/studio/a", listOf(0.75f)))
            }
            val buf = ByteArray(256)
            val p = DatagramPacket(buf, buf.size)
            receiver.receive(p)
            assertEquals(OscMessage("/studio/a", listOf(0.75f)), OscParser.parse(buf.copyOf(p.length)))
        }
    }

    @Test fun aSenderThatIsClosedSendsNothingAndDoesNotFail() {
        val s = OscSender(InetAddress.getLoopbackAddress(), 9)
        s.close()
        s.close() // closing twice is harmless
        assertEquals(false, s.send("/studio/a", listOf(1f)))
    }
}
