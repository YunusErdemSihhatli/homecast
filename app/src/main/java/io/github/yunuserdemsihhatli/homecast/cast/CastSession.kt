package io.github.yunuserdemsihhatli.homecast.cast

import io.github.yunuserdemsihhatli.homecast.cast.protocol.CastChannel
import io.github.yunuserdemsihhatli.homecast.cast.protocol.CastJson
import io.github.yunuserdemsihhatli.homecast.cast.protocol.CastJson.NS_CONNECTION
import io.github.yunuserdemsihhatli.homecast.cast.protocol.CastJson.NS_HEARTBEAT
import io.github.yunuserdemsihhatli.homecast.cast.protocol.CastJson.NS_MEDIA
import io.github.yunuserdemsihhatli.homecast.cast.protocol.CastJson.NS_RECEIVER
import io.github.yunuserdemsihhatli.homecast.cast.protocol.CastJson.RECEIVER_ID
import io.github.yunuserdemsihhatli.homecast.cast.protocol.CastJson.SENDER_ID
import io.github.yunuserdemsihhatli.homecast.cast.protocol.CastMessage
import io.github.yunuserdemsihhatli.homecast.cast.protocol.ReceiverApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Timing knobs of a [CastSession]; see [CastClient] for the defaults. */
internal data class CastTiming(
    val heartbeatIntervalMillis: Long,
    val heartbeatTimeoutMillis: Long,
    val requestTimeoutMillis: Long,
    val connectTimeoutMillis: Int,
)

/**
 * One TLS connection ([CastChannel]) and its virtual connections to receiver-0 and the Default Media
 * Receiver's transport. Runs exactly one long-lived collector of [CastChannel.incoming] (responses by
 * `requestId`, PING/PONG, status updates) and, once started, the heartbeat.
 *
 * Status updates go to [Host], which applies them only while this session is current, so a detached
 * session can never write shared state.
 */
internal class CastSession(
    val device: CastDevice,
    private val channel: CastChannel,
    parentScope: CoroutineScope,
    private val timing: CastTiming,
    private val requestIds: AtomicInteger,
    private val host: Host,
) {
    interface Host {
        /** Applies [transform] to the shared media status if [session] is still current. */
        fun updateMedia(
            session: CastSession,
            transform: (MediaStatus?) -> MediaStatus?,
        )

        /** Publishes [volume] if [session] is still current. */
        fun updateVolume(
            session: CastSession,
            volume: VolumeState,
        )

        /** The connection died (EOF, IO error, heartbeat timeout). Not called after [detach]. */
        fun sessionLost(session: CastSession)
    }

    private val sessionScope =
        CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val dead = AtomicBoolean(false)

    @Volatile private var detached = false
    private val appMutex = Mutex()

    /** The Default Media Receiver as last reported by RECEIVER_STATUS. */
    private val app = MutableStateFlow<ReceiverApp?>(null)

    @Volatile private var lastInboundNanos = System.nanoTime()

    /** Transport we sent CONNECT to; media messages go here. */
    @Volatile var connectedTransport: String? = null
        private set

    @Volatile var lossCause: CastException? = null
        private set

    val isDead get() = dead.get()

    /**
     * Connects receiver-0 and reads the receiver status. With [launchApp] the Default Media Receiver is
     * launched if it is not running; without it (reconnect) the transport is only re-joined if the app
     * is still running, so a reconnect never pulls the TV back to our receiver.
     */
    suspend fun open(launchApp: Boolean) {
        channel.connect(timing.connectTimeoutMillis)
        sessionScope.launch { readLoop() }
        send(NS_CONNECTION, RECEIVER_ID, CastJson.simple("CONNECT"))
        request(NS_RECEIVER, RECEIVER_ID, CastJson.simple("GET_STATUS"))
        if (launchApp || app.value != null) ensureApp()
    }

    /** Launches the receiver app if needed and connects to its transport; returns it. */
    suspend fun ensureApp(): ReceiverApp =
        appMutex.withLock {
            val running =
                app.value ?: run {
                    request(NS_RECEIVER, RECEIVER_ID, CastJson.launch())
                    withTimeoutOrNull(timing.requestTimeoutMillis) { app.filterNotNull().first() }
                        ?: throw CastTimeoutException("The Default Media Receiver did not start on ${device.name}")
                }
            if (connectedTransport != running.transportId) {
                send(NS_CONNECTION, running.transportId, CastJson.simple("CONNECT"))
                connectedTransport = running.transportId
                try {
                    request(NS_MEDIA, running.transportId, CastJson.simple("GET_STATUS")) // resumes media state
                } catch (_: CastRequestException) {
                    // No media status available; not fatal.
                }
            }
            running
        }

    fun startHeartbeat() {
        sessionScope.launch {
            while (true) {
                delay(timing.heartbeatIntervalMillis)
                if ((System.nanoTime() - lastInboundNanos) / 1_000_000 > timing.heartbeatTimeoutMillis) {
                    lost(CastException("${device.name} stopped responding"))
                    return@launch
                }
                try {
                    send(NS_HEARTBEAT, RECEIVER_ID, CastJson.simple("PING"))
                } catch (_: IOException) {
                    // A failed send ends the channel; readLoop reports the loss.
                }
            }
        }
    }

    suspend fun request(
        namespace: String,
        destination: String,
        payload: JsonObject,
    ): JsonObject {
        val id = requestIds.incrementAndGet()
        val response = CompletableDeferred<JsonObject>()
        pending[id] = response
        try {
            if (isDead) throw lossCause ?: CastException("Not connected to ${device.name}")
            send(namespace, destination, JsonObject(payload + ("requestId" to JsonPrimitive(id))))
            return withTimeoutOrNull(timing.requestTimeoutMillis) { response.await() }
                ?: throw CastTimeoutException("${CastJson.type(payload)} timed out on ${device.name}")
        } finally {
            pending.remove(id)
        }
    }

    /** Sends one message; a raw channel [IOException] is reported as a [CastException] (cause kept). */
    private suspend fun send(
        namespace: String,
        destination: String,
        payload: JsonObject,
    ) {
        try {
            channel.send(CastMessage(SENDER_ID, destination, namespace, payload.toString()))
        } catch (e: CastException) {
            throw e
        } catch (e: IOException) {
            throw CastException("Connection to ${device.name} failed", e)
        }
    }

    private suspend fun readLoop() {
        val cause =
            try {
                channel.incoming.collect(::handle)
                CastException("${device.name} closed the connection")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CastException("Connection to ${device.name} failed", e)
            }
        lost(cause)
    }

    private fun handle(m: CastMessage) {
        lastInboundNanos = System.nanoTime()
        val json = CastJson.parse(m.payloadUtf8) ?: return
        val type = CastJson.type(json)
        when (m.namespace) {
            NS_HEARTBEAT -> {
                if (type == "PING") {
                    sessionScope.launch {
                        try {
                            send(NS_HEARTBEAT, m.sourceId, CastJson.simple("PONG"))
                        } catch (_: IOException) {
                            // The channel is ending; readLoop reports it.
                        }
                    }
                }
            }

            NS_CONNECTION -> {
                if (type == "CLOSE" && m.sourceId == connectedTransport) appGone()
            }

            NS_RECEIVER -> {
                if (type == "RECEIVER_STATUS") {
                    val status = CastJson.parseReceiverStatus(json)
                    status.volume?.let { host.updateVolume(this, it) }
                    val newApp = status.app
                    if (newApp == null) {
                        appGone()
                    } else {
                        val transport = connectedTransport
                        if (transport != null && newApp.transportId != transport) {
                            // The app was replaced: the old transport (and its media) is gone, and a
                            // late CLOSE from it must not be mistaken for the new app closing.
                            connectedTransport = null
                            host.updateMedia(this) { null }
                        }
                        app.value = newApp
                    }
                }
            }

            NS_MEDIA -> {
                if (type == "MEDIA_STATUS") host.updateMedia(this) { CastJson.parseMediaStatus(json, it) }
            }
        }
        val id = CastJson.requestId(json)?.takeIf { it > 0 } ?: return
        val response = pending.remove(id) ?: return
        if (type in CastJson.ERROR_TYPES) {
            response.completeExceptionally(CastRequestException(type!!, CastJson.errorReason(json)))
        } else {
            response.complete(json)
        }
    }

    /** The receiver app was closed (on the TV or by another sender); we stay connected. */
    private fun appGone() {
        app.value = null
        connectedTransport = null
        host.updateMedia(this) { null }
    }

    private fun lost(cause: CastException) {
        if (!dead.compareAndSet(false, true)) return
        lossCause = cause
        failPending(cause)
        if (!detached) host.sessionLost(this)
    }

    private fun failPending(cause: CastException) {
        pending.values.forEach { it.completeExceptionally(cause) }
    }

    /** No longer owned by the client: losses are not reported. Call before [closeGracefully]. */
    fun detach() {
        detached = true
    }

    /** Best-effort CLOSE of the transport and receiver-0 (bounded to 1 s), then [close]. */
    suspend fun closeGracefully() {
        detach()
        withContext(NonCancellable) {
            if (!isDead) {
                withTimeoutOrNull(1_000) {
                    try {
                        connectedTransport?.let { send(NS_CONNECTION, it, CastJson.simple("CLOSE")) }
                        send(NS_CONNECTION, RECEIVER_ID, CastJson.simple("CLOSE"))
                    } catch (_: IOException) {
                        // Best effort; the socket is closed next anyway.
                    }
                }
            }
            close()
        }
    }

    /** Ends the session: fails pending requests, stops its coroutines, closes the channel on IO. */
    suspend fun close() {
        detach()
        dead.set(true)
        failPending(lossCause ?: CastException("Disconnected from ${device.name}"))
        sessionScope.cancel()
        withContext(NonCancellable + Dispatchers.IO) { channel.close() }
    }
}
