package dk.cbavf.studio.link

import dk.cbavf.studio.camera.model.CaptureInfo
import dk.cbavf.studio.patch.model.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Which side of the two-phone loop a phone plays. */
enum class LoopRole { Projector, Camera }

/** Synced: one capture per shown frame, in lockstep. Free-run: each phone on its own timer. */
enum class LoopMode { Synced, FreeRun }

/** Messages between the two apps (spec §6: live control socket). */
@Serializable
sealed interface LinkMessage {
    @Serializable
    @SerialName("hello")
    data class Hello(val device: String, val role: LoopRole) : LinkMessage

    /** Projector → camera: a loop starts. Free-run captures every [intervalMs]; [strobe] flickers the torch. */
    @Serializable
    @SerialName("start")
    data class Start(val mode: LoopMode, val intervalMs: Long, val strobe: Boolean) : LinkMessage

    /** Projector → camera: frame [seq] is on screen now (and has settled); capture it. */
    @Serializable
    @SerialName("show")
    data class Show(val seq: Int, val assetId: String?) : LinkMessage

    /** Camera → projector: the capture of frame [seq]; the JPEG travels as the payload. */
    @Serializable
    @SerialName("captured")
    data class Captured(val seq: Int, val info: CaptureInfo, val widthPx: Int, val heightPx: Int) : LinkMessage

    /** Either side: current raw sensor values, for patch routes that read the other phone (~20 Hz). */
    @Serializable
    @SerialName("sensors")
    data class Sensors(val values: Map<SourceKind, Float>) : LinkMessage

    /**
     * Either side: a picture from a routing-nodes Send to Other Phone node (the encoded JPEG or PNG
     * travels as the payload); the other phone's From Other Phone node shows the latest one.
     */
    @Serializable
    @SerialName("frame")
    data class Frame(val seq: Int, val widthPx: Int, val heightPx: Int) : LinkMessage

    /** Either side: the loop is over. */
    @Serializable
    @SerialName("stop")
    data object Stop : LinkMessage
}

/**
 * One open link. Frames are: JSON length, JSON, payload length, payload (0 = none).
 * Sends are serialized; receive from one coroutine only.
 */
class LinkConnection(private val socket: Socket) {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

    val peer: String get() = socket.inetAddress?.hostAddress ?: "?"

    suspend fun send(message: LinkMessage, payload: ByteArray? = null) = withContext(Dispatchers.IO) {
        val json = json.encodeToString(LinkMessage.serializer(), message).toByteArray()
        synchronized(output) {
            output.writeInt(json.size)
            output.write(json)
            output.writeInt(payload?.size ?: 0)
            payload?.let { output.write(it) }
            output.flush()
        }
    }

    /** Next message and its payload; throws [EOFException] when the other side hangs up. */
    suspend fun receive(): Pair<LinkMessage, ByteArray?> = withContext(Dispatchers.IO) {
        val n = input.readInt()
        require(n in 1..MAX_JSON) { "bad frame" }
        val text = ByteArray(n).also { input.readFully(it) }.decodeToString()
        val size = input.readInt()
        require(size in 0..MAX_PAYLOAD) { "bad payload" }
        val payload = if (size == 0) null else ByteArray(size).also { input.readFully(it) }
        json.decodeFromString(LinkMessage.serializer(), text) to payload
    }

    fun close() = runCatching { socket.close() }

    companion object {
        private const val MAX_JSON = 1 shl 20
        private const val MAX_PAYLOAD = 64 shl 20
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }
}

object Link {
    const val PORT = 47800

    /** Waits for the other phone to connect. Cancel by closing [server]. */
    suspend fun accept(server: ServerSocket): LinkConnection = withContext(Dispatchers.IO) {
        LinkConnection(server.accept().apply { tcpNoDelay = true })
    }

    fun listen(port: Int = PORT): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port))
    }

    suspend fun connect(host: String, port: Int = PORT, timeoutMs: Int = 5_000): LinkConnection = withContext(Dispatchers.IO) {
        LinkConnection(Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), timeoutMs)
        })
    }
}
