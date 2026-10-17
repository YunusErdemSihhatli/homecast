package io.github.yunuserdemsihhatli.homecast.playback

import io.github.yunuserdemsihhatli.homecast.capture.SubtitleFormat
import io.github.yunuserdemsihhatli.homecast.capture.SubtitleSource
import io.github.yunuserdemsihhatli.homecast.capture.VideoCandidate
import io.github.yunuserdemsihhatli.homecast.cast.CastConnectException
import io.github.yunuserdemsihhatli.homecast.cast.CastDevice
import io.github.yunuserdemsihhatli.homecast.cast.CastException
import io.github.yunuserdemsihhatli.homecast.cast.CastRemote
import io.github.yunuserdemsihhatli.homecast.cast.ConnectionState
import io.github.yunuserdemsihhatli.homecast.cast.DeviceConnector
import io.github.yunuserdemsihhatli.homecast.cast.DeviceStore
import io.github.yunuserdemsihhatli.homecast.cast.LoadRequest
import io.github.yunuserdemsihhatli.homecast.cast.MediaStatus
import io.github.yunuserdemsihhatli.homecast.cast.StreamType
import io.github.yunuserdemsihhatli.homecast.proxy.ProxyException
import io.github.yunuserdemsihhatli.homecast.proxy.ProxyServer
import io.github.yunuserdemsihhatli.homecast.proxy.ProxySession
import io.github.yunuserdemsihhatli.homecast.subtitles.SrtToVtt
import io.github.yunuserdemsihhatli.homecast.subtitles.SubtitleDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** What is on the TV right now. [session] lists every subtitle track the TV knows about. */
data class NowPlaying(
    val candidate: VideoCandidate,
    val session: ProxySession,
    val title: String,
)

sealed interface PlaybackError {
    /** The phone has no Wi-Fi IPv4 address, so the TV could not reach the proxy. */
    data object NoWifi : PlaybackError

    /** No Cast device is connected and none was used before. */
    data object NoDevice : PlaybackError

    data class Proxy(
        val reason: ProxyException.Reason,
    ) : PlaybackError

    /** The TV could not be reached at all: switched off, at another address, or on another network. */
    data class CannotReachTv(
        val deviceName: String,
    ) : PlaybackError

    data class Cast(
        val message: String,
    ) : PlaybackError

    /** The picked subtitle file holds no usable SRT/WebVTT cues. */
    data object InvalidSubtitle : PlaybackError

    /** An unexpected failure inside the app, e.g. the proxy session vanished. */
    data class Internal(
        val message: String,
    ) : PlaybackError
}

/**
 * The "Play on TV" use case: proxies a captured video, LOADs it on the Cast device and keeps the two in step.
 *
 * Owns the proxy lifecycle (started lazily on the first [playOnTv]) and keeps its advertised host equal to the
 * phone's current Wi-Fi address. Public operations never throw: failures are emitted on [errors]. Operations that
 * change what is playing are serialised, so a double tap or a quick episode switch cannot interleave; [stop] cancels
 * a [playOnTv] or [addSubtitleFile] that is still in flight (or queued) instead of waiting behind it.
 */
class PlaybackController(
    scope: CoroutineScope,
    private val proxy: ProxyServer,
    private val cast: CastRemote,
    private val wifi: WifiAddressProvider,
    private val devices: DeviceStore,
    private val serviceStarter: () -> Unit,
    private val serviceStopper: () -> Unit,
    private val proxyBindHost: String = "0.0.0.0",
    discover: () -> Flow<List<CastDevice>> = { emptyFlow() },
    discoveryTimeoutMillis: Long = DeviceConnector.DEFAULT_DISCOVERY_TIMEOUT_MILLIS,
) {
    private val mutex = Mutex()
    private val connector = DeviceConnector(cast, devices, discover, discoveryTimeoutMillis)

    /** The running cancellable operation, cancelled by [stop]. */
    @Volatile private var currentOp: Job? = null

    /** Bumped by every [stop]; cancellable operations requested before it are dropped. */
    private val stopGeneration = AtomicInteger()
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    private val _errors =
        MutableSharedFlow<PlaybackError>(
            extraBufferCapacity = ERROR_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private val _starting = MutableStateFlow(false)

    /** The last media status the TV reported for [nowPlaying], kept after the TV forgets it, for [playAgain]. */
    @Volatile private var lastMedia: MediaStatus? = null

    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()
    val errors: SharedFlow<PlaybackError> = _errors.asSharedFlow()

    /**
     * True while a [playOnTv] or [playAgain] is in flight. The playback service is started when one begins (while the
     * user is still in the app) and must not give up while this is true, even though [nowPlaying] may still be null.
     */
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    init {
        // A lost address keeps the last one: the TV may still be playing from its buffer.
        scope.launch { wifi.address.filterNotNull().collect(proxy::setAdvertisedHost) }
        scope.launch { cast.media.filterNotNull().collect { lastMedia = it } }
    }

    /** Proxies [candidate] and LOADs it, replacing (and closing) whatever was playing. */
    suspend fun playOnTv(
        candidate: VideoCandidate,
        subtitles: List<SubtitleSource>,
        activeSubtitleIndex: Int? = null,
    ) = guarded(cancellableByStop = true) {
        val address = currentAddress() ?: return@guarded emit(PlaybackError.NoWifi)
        startingService {
            if (!ensureConnected()) return@startingService emit(PlaybackError.NoDevice)
            startProxy(address)
            play(candidate, subtitles, activeSubtitleIndex)
        }
    }

    private suspend fun play(
        candidate: VideoCandidate,
        subtitles: List<SubtitleSource>,
        activeSubtitleIndex: Int?,
    ) {
        val session = proxy.createSession(candidate, subtitles)
        val activeTracks = listOfNotNull(activeSubtitleIndex?.let { session.subtitles.getOrNull(it)?.trackId })
        val title = candidate.pageTitle.ifBlank { candidate.url.substringAfterLast('/').substringBefore('?') }
        try {
            cast.load(loadRequest(session, title, activeTracks, startTimeSeconds = 0.0))
        } catch (e: Exception) {
            // including cancellation by stop()
            proxy.closeSession(session.token)
            throw e
        }
        val previous = _nowPlaying.value
        lastMedia = cast.media.value
        _nowPlaying.value = NowPlaying(candidate, session, title)
        if (previous != null && previous.session.token != session.token) proxy.closeSession(previous.session.token)
    }

    /**
     * LOADs [nowPlaying] again, e.g. after the TV dropped it (receiver app closed, connection lost and back, video
     * ended): same proxy session, rehosted to the current Wi-Fi address, from the last known position (live streams
     * from the live edge; a video that had finished from the start) with the same subtitle track.
     */
    suspend fun playAgain() =
        guarded(cancellableByStop = true) {
            val playing = _nowPlaying.value ?: return@guarded
            val address = currentAddress() ?: return@guarded emit(PlaybackError.NoWifi)
            startingService {
                if (!ensureConnected()) return@startingService emit(PlaybackError.NoDevice)
                startProxy(address)
                val last = lastMedia
                val session = playing.session.rehosted(address)
                val position = if (last == null || last.idleReason == IDLE_FINISHED) 0.0 else last.currentTimeSeconds
                val known = session.subtitles.mapTo(HashSet()) { it.trackId }
                val tracks = last?.activeTrackIds.orEmpty().filter { it in known }
                cast.load(loadRequest(session, playing.title, tracks, position))
                _nowPlaying.value = playing.copy(session = session)
            }
        }

    /**
     * Starts the playback service before [block] (while the user is still in the app, so a foreground start is
     * allowed), keeps [starting] true while it runs, and starts the service again after it succeeded in case it went
     * away meanwhile (starting a running service only refreshes it).
     */
    private suspend fun startingService(block: suspend () -> Unit) {
        _starting.value = true
        try {
            serviceStarter()
            block()
            if (_nowPlaying.value != null) serviceStarter()
        } finally {
            _starting.value = false
        }
    }

    private suspend fun startProxy(address: String) {
        withContext(Dispatchers.IO) { proxy.start(proxyBindHost) }
        proxy.setAdvertisedHost(address)
    }

    /**
     * Adds a subtitle file picked by the user and re-LOADs at the current position with it switched on. When the TV
     * has no media (playback was stopped there) the track is only registered: stopped playback is not restarted.
     */
    suspend fun addSubtitleFile(
        bytes: ByteArray,
        label: String,
    ) = guarded(cancellableByStop = true) {
        val playing = _nowPlaying.value ?: return@guarded
        val address = currentAddress() ?: return@guarded emit(PlaybackError.NoWifi)
        val vtt =
            withContext(Dispatchers.Default) { toVtt(bytes) } ?: return@guarded emit(PlaybackError.InvalidSubtitle)
        val source =
            SubtitleSource(
                id = "file-${UUID.randomUUID()}",
                label = label,
                language = null,
                format = SubtitleFormat.VTT,
                inlineVtt = vtt,
            )
        val added = proxy.addSubtitle(playing.session.token, source)
        val session = playing.session.copy(subtitles = playing.session.subtitles + added).rehosted(address)
        val media = cast.media.value
        if (media != null) {
            cast.load(loadRequest(session, playing.title, listOf(added.trackId), media.currentTimeSeconds))
        }
        _nowPlaying.value = playing.copy(session = session)
    }

    /** Switches the TV to subtitle [trackId]; null turns subtitles off. */
    suspend fun selectSubtitle(trackId: Int?) =
        guarded {
            cast.setActiveTracks(listOfNotNull(trackId))
        }

    /** Cancels a pending play, stops playback on the TV, closes the proxy session and stops the playback service. */
    suspend fun stop() {
        stopGeneration.incrementAndGet()
        currentOp?.cancel()
        guarded {
            val playing = _nowPlaying.value
            try {
                if (cast.media.value != null) cast.stopMedia()
            } finally {
                playing?.let { proxy.closeSession(it.session.token) }
                lastMedia = null
                _nowPlaying.value = null
                serviceStopper()
            }
        }
    }

    /** The Wi-Fi address, waiting briefly for the first network callback right after app start. */
    private suspend fun currentAddress(): String? =
        wifi.address.value ?: withTimeoutOrNull(WIFI_WAIT_MILLIS) { wifi.address.filterNotNull().first() }

    /**
     * True once a Cast device is connected, connecting to the last used one if necessary (and following it to a new
     * address found by discovery if the saved one no longer answers).
     */
    private suspend fun ensureConnected(): Boolean {
        var state = cast.connection.value
        if (state is ConnectionState.Connecting) {
            // Never abandon a device the user just picked for the last used one.
            state = withTimeoutOrNull(CONNECTING_WAIT_MILLIS) {
                cast.connection.first { it !is ConnectionState.Connecting }
            } ?: throw CastException("Still connecting to ${state.device.name}")
        }
        if (state is ConnectionState.Connected) return true
        val device = devices.lastUsed() ?: return false
        connector.connect(device)
        return true
    }

    private fun loadRequest(
        session: ProxySession,
        title: String,
        activeTrackIds: List<Int>,
        startTimeSeconds: Double,
    ) = LoadRequest(
        url = session.mediaUrl,
        contentType = session.contentType,
        streamType = if (session.isLive) StreamType.LIVE else StreamType.BUFFERED,
        title = title,
        subtitles = session.subtitles,
        activeTrackIds = activeTrackIds,
        startTimeSeconds = if (session.isLive) 0.0 else startTimeSeconds,
        hlsSegmentFormat = session.hlsSegmentFormat,
    )

    private fun emit(error: PlaybackError) {
        _errors.tryEmit(error)
    }

    /**
     * Runs [block] under the mutex, reporting failures on [errors]. A [cancellableByStop] block runs as its own job
     * so [stop] can cancel it; if a stop was requested after this call was made, the block does not run at all.
     */
    private suspend fun guarded(
        cancellableByStop: Boolean = false,
        block: suspend () -> Unit,
    ) {
        val generation = stopGeneration.get()
        mutex.withLock {
            if (!cancellableByStop) return@withLock reporting(block)
            coroutineScope {
                val op = launch { reporting(block) }
                currentOp = op
                // stop() bumps the generation before reading currentOp, so one of the two always sees the other.
                if (generation != stopGeneration.get()) op.cancel()
                try {
                    op.join()
                } finally {
                    if (currentOp === op) currentOp = null
                }
            }
        }
    }

    private suspend fun reporting(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProxyException) {
            emit(PlaybackError.Proxy(e.reason))
        } catch (e: CastConnectException) {
            emit(PlaybackError.CannotReachTv(e.device.name))
        } catch (e: CastException) {
            emit(PlaybackError.Cast(e.describe()))
        } catch (e: Exception) {
            emit(PlaybackError.Internal(e.describe()))
        }
    }

    private companion object {
        const val ERROR_BUFFER = 8
        const val IDLE_FINISHED = "FINISHED"
        const val CONNECTING_WAIT_MILLIS = 10_000L
        const val WIFI_WAIT_MILLIS = 2_000L
        private const val CUE_TIMING = "-->"

        fun Exception.describe() = message ?: javaClass.simpleName

        /** Decoded WebVTT, or null when the file holds no cue at all (not a subtitle file). */
        fun toVtt(bytes: ByteArray): String? =
            try {
                SrtToVtt.ensureVtt(SubtitleDecoder.decode(bytes)).takeIf { CUE_TIMING in it }
            } catch (_: Exception) {
                null
            }

        /** Proxy URLs carry the Wi-Fi address they were built with; re-LOADs after a network change need the new one. */
        fun ProxySession.rehosted(host: String): ProxySession =
            copy(
                mediaUrl = mediaUrl.withHost(host),
                subtitles = subtitles.map { it.copy(url = it.url.withHost(host)) },
            )

        fun String.withHost(host: String): String =
            toHttpUrlOrNull()
                ?.newBuilder()
                ?.host(host)
                ?.build()
                ?.toString() ?: this
    }
}
