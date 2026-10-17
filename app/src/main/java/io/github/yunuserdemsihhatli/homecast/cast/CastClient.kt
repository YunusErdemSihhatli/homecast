package io.github.yunuserdemsihhatli.homecast.cast

import io.github.yunuserdemsihhatli.homecast.cast.protocol.CastChannel
import io.github.yunuserdemsihhatli.homecast.cast.protocol.CastJson
import io.github.yunuserdemsihhatli.homecast.cast.protocol.CastJson.NS_MEDIA
import io.github.yunuserdemsihhatli.homecast.cast.protocol.CastJson.NS_RECEIVER
import io.github.yunuserdemsihhatli.homecast.cast.protocol.CastJson.RECEIVER_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * CASTV2 sender talking to the Default Media Receiver over a [CastChannel].
 *
 * Each TLS connection is a [CastSession] with exactly one long-lived collector of
 * [CastChannel.incoming] and a heartbeat that sends PING every [heartbeatIntervalMillis]. A dead
 * connection (EOF, IO error, or no inbound message for [heartbeatTimeoutMillis]) is replaced by a new
 * channel from [channelFactory], with one attempt after each delay in [reconnectBackoffMillis]; when
 * all fail the state becomes [ConnectionState.Failed]. A reconnect never launches the receiver app.
 *
 * Only the session owned by the current generation may write [media] and [volume]; every connect,
 * disconnect and reconnect starts a new generation, so detached sessions cannot leak state.
 */
class CastClient(
    private val scope: CoroutineScope,
    private val channelFactory: (CastDevice) -> CastChannel = { CastChannel(it.host, it.port) },
    heartbeatIntervalMillis: Long = 5_000,
    heartbeatTimeoutMillis: Long = 15_000,
    requestTimeoutMillis: Long = 10_000,
    private val reconnectBackoffMillis: List<Long> = listOf(1_000, 2_000, 4_000),
    connectTimeoutMillis: Int = 5_000,
) : CastRemote {
    private val timing =
        CastTiming(heartbeatIntervalMillis, heartbeatTimeoutMillis, requestTimeoutMillis, connectTimeoutMillis)

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _media = MutableStateFlow<MediaStatus?>(null)
    override val media: StateFlow<MediaStatus?> = _media.asStateFlow()

    private val _volume = MutableStateFlow<VolumeState?>(null)
    override val volume: StateFlow<VolumeState?> = _volume.asStateFlow()

    private val requestIds = AtomicInteger(0)

    /**
     * Serializes connection lifecycle transitions; guards [session] writes and [reconnectJob].
     * Internal only so tests can hold it to force lock contention.
     */
    internal val lifecycle = Mutex()

    /** Makes "is this session still the owner?" and the state write it allows atomic. */
    private val stateLock = Any()

    /** Bumped (under [lifecycle] and [stateLock]) by every connect, disconnect and reconnect. */
    @Volatile private var generation = 0L

    /** The session allowed to write [media]/[volume] (opening or installed); guarded by [stateLock]. */
    private var owner: CastSession? = null

    /** The installed (Connected) session; null while connecting, reconnecting or disconnected. */
    @Volatile private var session: CastSession? = null
    private var reconnectJob: Job? = null

    private val host =
        object : CastSession.Host {
            override fun updateMedia(
                session: CastSession,
                transform: (MediaStatus?) -> MediaStatus?,
            ) = synchronized(stateLock) { if (owner === session) _media.value = transform(_media.value) }

            override fun updateVolume(
                session: CastSession,
                volume: VolumeState,
            ) = synchronized(stateLock) { if (owner === session) _volume.value = volume }

            override fun sessionLost(session: CastSession) {
                scope.launch { reconnect(session) }
            }
        }

    override suspend fun connect(device: CastDevice) {
        val (gen, old) =
            lifecycle.withLock {
                val old = detachLocked()
                _connection.value = ConnectionState.Connecting(device)
                advanceLocked(clearState = true) to old
            }
        old?.closeGracefully()
        val s =
            try {
                openSession(device, gen, launchApp = true)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    lifecycle.withLock { if (generation == gen) _connection.value = ConnectionState.Disconnected }
                }
                throw e
            } catch (e: Exception) {
                lifecycle.withLock {
                    if (generation == gen) _connection.value = ConnectionState.Failed(device, describe(e))
                }
                throw e as? CastConnectException ?: CastConnectException(device, e)
            }
        if (!installOrClose(s, gen)) throw CastException("Connection to ${device.name} was superseded")
    }

    override suspend fun load(request: LoadRequest) {
        val s = current()
        val app = s.ensureApp()
        s.request(NS_MEDIA, app.transportId, CastJson.load(app.sessionId, request))
    }

    override suspend fun play() = mediaCommand { CastJson.mediaCommand("PLAY", it) }

    override suspend fun pause() = mediaCommand { CastJson.mediaCommand("PAUSE", it) }

    override suspend fun seek(seconds: Double) = mediaCommand { CastJson.seek(it, seconds) }

    override suspend fun stopMedia() = mediaCommand { CastJson.mediaCommand("STOP", it) }

    override suspend fun setActiveTracks(trackIds: List<Int>) = mediaCommand { CastJson.editTracks(it, trackIds) }

    override suspend fun setVolume(level: Double) {
        current().request(NS_RECEIVER, RECEIVER_ID, CastJson.setVolumeLevel(level))
    }

    override suspend fun setMuted(muted: Boolean) {
        current().request(NS_RECEIVER, RECEIVER_ID, CastJson.setMuted(muted))
    }

    override suspend fun disconnect() {
        val old =
            lifecycle.withLock {
                val old = detachLocked()
                advanceLocked(clearState = true)
                _connection.value = ConnectionState.Disconnected
                old
            }
        old?.closeGracefully()
    }

    private suspend fun mediaCommand(build: (mediaSessionId: Int) -> JsonObject) {
        val s = current()
        val mediaSessionId = _media.value?.mediaSessionId ?: throw CastException("No media is loaded")
        val transport = s.connectedTransport ?: throw CastException("The receiver app is not running")
        s.request(NS_MEDIA, transport, build(mediaSessionId))
    }

    private fun current(): CastSession = session ?: throw CastException("Not connected to a Cast device")

    /** Stops reconnecting and detaches the installed session; the caller closes it outside the lock. */
    private fun detachLocked(): CastSession? {
        reconnectJob?.cancel()
        reconnectJob = null
        val old = session
        session = null
        old?.detach()
        return old
    }

    /** Starts a new generation: no existing session may write state any more. */
    private fun advanceLocked(clearState: Boolean): Long =
        synchronized(stateLock) {
            owner = null
            if (clearState) {
                _media.value = null
                _volume.value = null
            }
            ++generation
        }

    /** Opens a session for generation [gen]; it owns the shared state unless [gen] is already stale. */
    private suspend fun openSession(
        device: CastDevice,
        gen: Long,
        launchApp: Boolean,
    ): CastSession {
        val s = CastSession(device, channelFactory(device), scope, timing, requestIds, host)
        try {
            val owned = synchronized(stateLock) { (generation == gen).also { if (it) owner = s } }
            if (!owned) throw CastException("Connection to ${device.name} was superseded")
            s.open(launchApp)
            return s
        } catch (e: Throwable) {
            s.close()
            throw e
        }
    }

    /**
     * Installs [s] if [gen] is still current, otherwise closes it. Not cancellable, so a session that
     * finished opening is never left running unowned when the caller is cancelled at this point.
     */
    private suspend fun installOrClose(
        s: CastSession,
        gen: Long,
    ): Boolean {
        var installed = false
        try {
            withContext(NonCancellable) {
                lifecycle.withLock {
                    if (generation == gen) {
                        installLocked(s)
                        installed = true
                    }
                }
            }
        } finally {
            if (!installed) s.close()
        }
        return installed
    }

    private fun installLocked(s: CastSession) {
        reconnectJob = null
        session = s
        _connection.value = ConnectionState.Connected(s.device)
        s.startHeartbeat()
        // Lost between opening and installing: its loss was ignored as stale, so handle it now.
        if (s.isDead) scope.launch { reconnect(s) }
    }

    private suspend fun reconnect(lost: CastSession) {
        val device = lost.device
        val gen =
            lifecycle.withLock {
                if (session !== lost) return
                session = null
                lost.detach()
                reconnectJob = currentCoroutineContext()[Job]
                _connection.value = ConnectionState.Connecting(device)
                advanceLocked(clearState = false) // keep the last status on screen while reconnecting
            }
        lost.close()
        var lastError: Exception = lost.lossCause ?: CastException("Connection lost")
        for (backoff in reconnectBackoffMillis) {
            delay(backoff)
            val s =
                try {
                    openSession(device, gen, launchApp = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    continue
                }
            installOrClose(s, gen)
            return
        }
        lifecycle.withLock {
            if (generation == gen) {
                reconnectJob = null
                advanceLocked(clearState = true)
                _connection.value = ConnectionState.Failed(device, describe(lastError))
            }
        }
    }

    private fun describe(e: Exception) = e.message ?: e.javaClass.simpleName
}
