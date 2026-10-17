package io.github.yunuserdemsihhatli.homecast.cast.protocol

import android.annotation.SuppressLint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** Largest frame (protobuf body) accepted from the receiver. */
const val MAX_CAST_FRAME_BYTES = 64 * 1024

/**
 * TLS channel to a Chromecast (CASTV2, port 8009). Frames are a 4-byte big-endian length followed by
 * the protobuf body ([CastMessageCodec]).
 */
class CastChannel(
    private val host: String,
    private val port: Int,
    private val sslSocketFactory: SSLSocketFactory = insecureCastSocketFactory(),
) : Closeable {
    private sealed interface Event {
        class Message(
            val message: CastMessage,
        ) : Event

        /** Terminal: normal EOF or [close]. */
        data object Ended : Event

        /** Terminal: IO failure or protocol violation. */
        class Failed(
            val error: IOException,
        ) : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = MutableSharedFlow<Event>(extraBufferCapacity = 64)

    /** Forced termination (close, failed send). A state, so it can never be dropped by a full buffer. */
    private val aborted = MutableStateFlow<Event?>(null)
    private val writeMutex = Mutex()
    private val lock = Any()

    // Guarded by [lock]: terminal, socket, output.
    private var terminal: Event? = null
    private var socket: SSLSocket? = null

    @Volatile private var output: DataOutputStream? = null

    /** Thrown inside [incoming]'s scope to unwind a collection when the channel is aborted. */
    private class AbortSignal(
        val event: Event,
    ) : RuntimeException("channel aborted")

    /**
     * Hot stream of received messages, fed by a single reader coroutine started by [connect].
     *
     * Subscribing is deterministic: the subscription is registered synchronously when collection
     * starts, even from an undispatched or later-dispatched `launch`. The reader never publishes a
     * frame while nobody is subscribed; it holds it (and stops reading) until the first collector
     * arrives, so frames received before anyone collects are delivered, not dropped. There is no
     * replay beyond that: once at least one collector is active, a collector that subscribes later only
     * sees frames that arrive after it subscribed. Several collectors may run at once and each sees
     * every message. A stalled collector back-pressures the reader and therefore ALL collectors
     * (buffer of 64 frames).
     *
     * Completes on EOF or [close]; fails with the [IOException] on a read error, an oversized or
     * malformed frame, or a failed [send]. [close] and failed sends end all collectors promptly even if
     * they are stalled (frames still buffered for them may then be dropped). Collecting after the
     * channel ended completes (or fails) immediately. The first terminal state wins.
     */
    val incoming: Flow<CastMessage> =
        flow {
            try {
                coroutineScope {
                    val watcher =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            throw AbortSignal(aborted.filterNotNull().first())
                        }
                    events
                        .onSubscription { currentTerminal()?.let { emit(it) } }
                        .transformWhile { event ->
                            when (event) {
                                is Event.Message -> {
                                    emit(event.message)
                                    true
                                }

                                Event.Ended -> {
                                    false
                                }

                                is Event.Failed -> {
                                    throw event.error
                                }
                            }
                        }.collect { emit(it) }
                    watcher.cancel()
                }
            } catch (e: AbortSignal) {
                (e.event as? Event.Failed)?.let { throw it.error }
            }
        }

    private fun currentTerminal(): Event? = synchronized(lock) { terminal }

    suspend fun connect(timeoutMillis: Int = 5000) {
        val raw = sslSocketFactory.createSocket() as SSLSocket
        synchronized(lock) {
            if (terminal != null || socket != null) {
                runCatching { raw.close() }
                throw IOException(if (terminal != null) "Channel closed" else "Already connected")
            }
            socket = raw // published first so close() can interrupt a connect in progress
        }
        try {
            coroutineScope {
                val work =
                    async(Dispatchers.IO) {
                        try {
                            raw.connect(InetSocketAddress(host, port), timeoutMillis)
                            raw.soTimeout = timeoutMillis // bounds the handshake
                            raw.startHandshake()
                            raw.soTimeout = 0
                            raw.tcpNoDelay = true
                        } catch (e: IOException) {
                            // Closing the socket on cancellation must not turn into a real failure.
                            if (!isActive) throw CancellationException("connect cancelled").apply { initCause(e) }
                            throw e
                        }
                    }
                try {
                    work.await()
                } catch (e: CancellationException) {
                    runCatching { raw.close() } // unblocks the blocking connect/handshake
                    throw e
                }
            }
            // Both streams are taken here, where a concurrent close() surfaces as this connect's failure.
            val input = raw.inputStream
            val out = DataOutputStream(raw.outputStream.buffered())
            synchronized(lock) {
                if (terminal != null) throw IOException("Channel closed")
                output = out
                scope.launch { readLoop(input) }
            }
        } catch (e: Throwable) {
            runCatching { raw.close() }
            synchronized(lock) { if (socket === raw && output == null) socket = null }
            if (e is CancellationException) throw e
            throw if (e is IOException) e else IOException(e)
        }
    }

    /** Safe to call from several coroutines; frames are never interleaved. Throws once closed. */
    suspend fun send(m: CastMessage) {
        val body = CastMessageCodec.encode(m)
        writeMutex.withLock {
            val out = output
            if (out == null ||
                synchronized(lock) { terminal } != null
            ) {
                throw IOException("Channel closed or not connected")
            }
            withContext(Dispatchers.IO) {
                try {
                    out.writeInt(body.size)
                    out.write(body)
                    out.flush()
                } catch (e: IOException) {
                    abort(Event.Failed(e))
                    throw e
                }
            }
        }
    }

    private suspend fun readLoop(input: InputStream) {
        var end: Event
        try {
            while (true) {
                val body = readFrame(input) ?: break
                val message = CastMessageCodec.decode(body)
                // Hold the frame until someone is subscribed rather than dropping it.
                if (events.subscriptionCount.value == 0) events.subscriptionCount.first { it > 0 }
                events.emit(Event.Message(message))
            }
            end = Event.Ended
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A read failing because close()/abort shut the socket is not a new error.
            if (aborted.value != null) return
            end = Event.Failed(e as? IOException ?: IOException(e))
        }
        val won =
            synchronized(lock) {
                (terminal == null).also { if (it) terminal = end }
            }
        runCatching { synchronized(lock) { socket }?.close() }
        if (won) events.emit(end)
    }

    /** Returns null on a clean EOF at a frame boundary. */
    private fun readFrame(input: InputStream): ByteArray? {
        val first = input.read()
        if (first < 0) return null
        val header = ByteArray(4)
        header[0] = first.toByte()
        readFully(input, header, 1, 3)
        val length =
            ((header[0].toInt() and 0xFF) shl 24) or ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        if (length < 0 || length > MAX_CAST_FRAME_BYTES) {
            throw IOException("Cast frame of $length bytes exceeds the $MAX_CAST_FRAME_BYTES byte limit")
        }
        val body = ByteArray(length)
        readFully(input, body, 0, length)
        return body
    }

    private fun readFully(
        input: InputStream,
        buf: ByteArray,
        offset: Int,
        length: Int,
    ) {
        var done = 0
        while (done < length) {
            val n = input.read(buf, offset + done, length - done)
            if (n < 0) throw EOFException("Connection closed mid-frame")
            done += n
        }
    }

    /** Non-suspending forced termination; collectors are ended through [aborted]. */
    private fun abort(event: Event) {
        val (winner, sock) =
            synchronized(lock) {
                if (terminal == null) terminal = event
                terminal!! to socket
            }
        runCatching { sock?.close() }
        // Publish whichever terminal state came first, e.g. a read failure is not turned into Ended by close().
        aborted.compareAndSet(null, winner)
    }

    override fun close() {
        abort(Event.Ended)
        scope.cancel()
    }
}

/**
 * Trust-all TLS socket factory for [CastChannel] ONLY.
 *
 * Chromecasts present device-generated self-signed certificates that no public CA signs, so normal
 * chain validation can never succeed. The peer is addressed by LAN IP, so no hostname verification
 * is performed either (plain [SSLSocket]s do none unless an endpoint-identification algorithm is
 * set). The context is created per call and never installed as the JVM/default one.
 */
fun insecureCastSocketFactory(): SSLSocketFactory {
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf(TrustAnyCastDevice), SecureRandom())
    return context.socketFactory
}

/** Accepts any certificate. Private to this file; see [insecureCastSocketFactory] for the rationale. */
@SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
private object TrustAnyCastDevice : X509TrustManager {
    // Intentionally empty: Cast devices use self-signed certificates.
    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) = Unit

    // Intentionally empty: Cast devices use self-signed certificates.
    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
