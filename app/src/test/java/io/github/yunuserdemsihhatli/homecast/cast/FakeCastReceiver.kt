package io.github.yunuserdemsihhatli.homecast.cast

import io.github.yunuserdemsihhatli.homecast.cast.protocol.CastMessage
import io.github.yunuserdemsihhatli.homecast.cast.protocol.CastMessageCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * In-process TLS server standing in for a Chromecast (self-signed certificate, 127.0.0.1, ephemeral
 * port). Accepts one connection at a time; a new connection replaces the previous one.
 */
class FakeCastReceiver : Closeable {
    /** Every frame the client sent, in order. */
    val received = Channel<CastMessage>(Channel.UNLIMITED)

    /** Replies written back for each received message (default: none). */
    @Volatile
    var autoRespond: (CastMessage) -> List<CastMessage> = { emptyList() }

    private var server: SSLServerSocket? = null

    @Volatile private var client: Socket? = null

    @Volatile private var out: DataOutputStream? = null
    private val writeLock = Any()

    /** Starts listening and returns the port. */
    fun start(): Int {
        val cert = HeldCertificate.Builder().commonName("fake-chromecast").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val s =
            tls
                .sslContext()
                .serverSocketFactory
                .createServerSocket(0, 5, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        server = s
        thread(isDaemon = true, name = "fake-cast-accept") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                runCatching { client?.close() }
                client = c
                out = DataOutputStream(c.getOutputStream().buffered())
                thread(isDaemon = true, name = "fake-cast-read") { serve(c) }
            }
        }
        return s.localPort
    }

    private fun serve(c: Socket) {
        try {
            val input = DataInputStream(c.getInputStream().buffered())
            while (true) {
                val body = ByteArray(input.readInt())
                input.readFully(body)
                val message = CastMessageCodec.decode(body)
                received.trySend(message)
                val replies =
                    try {
                        autoRespond(message)
                    } catch (e: Throwable) {
                        // Surface to the test: the next received.receive() throws this.
                        received.close(AssertionError("autoRespond threw", e))
                        c.close()
                        return
                    }
                replies.forEach { writeFrame(CastMessageCodec.encode(it)) }
            }
        } catch (_: IOException) {
            // Client went away or we dropped it.
        }
    }

    /** Waits (bounded) for a client to be connected, then sends [m]. */
    suspend fun send(m: CastMessage) = sendRaw(frame(CastMessageCodec.encode(m)))

    /** Writes arbitrary bytes to the client, e.g. a bogus length prefix. */
    suspend fun sendRaw(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            val deadline = System.nanoTime() + 2_000_000_000L
            while (out == null && System.nanoTime() < deadline) Thread.sleep(10)
            val o = out ?: throw IOException("No client connected")
            synchronized(writeLock) {
                o.write(bytes)
                o.flush()
            }
        }
    }

    private fun writeFrame(body: ByteArray) {
        val o = out ?: return
        synchronized(writeLock) {
            o.write(frame(body))
            o.flush()
        }
    }

    private fun frame(body: ByteArray): ByteArray {
        val size = body.size
        return byteArrayOf(
            (size ushr 24).toByte(),
            (size ushr 16).toByte(),
            (size ushr 8).toByte(),
            size.toByte(),
        ) + body
    }

    /** Abruptly closes the current client connection (the client sees EOF). */
    fun dropConnection() {
        runCatching { client?.close() }
        client = null
        out = null
    }

    override fun close() {
        dropConnection()
        runCatching { server?.close() }
        received.close()
    }
}
