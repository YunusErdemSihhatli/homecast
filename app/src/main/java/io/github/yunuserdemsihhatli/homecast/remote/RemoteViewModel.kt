package io.github.yunuserdemsihhatli.homecast.remote

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.yunuserdemsihhatli.homecast.HomeCastApp
import io.github.yunuserdemsihhatli.homecast.cast.CastDevice
import io.github.yunuserdemsihhatli.homecast.cast.CastRemote
import io.github.yunuserdemsihhatli.homecast.cast.ConnectionState
import io.github.yunuserdemsihhatli.homecast.cast.DeviceConnector
import io.github.yunuserdemsihhatli.homecast.cast.DeviceStore
import io.github.yunuserdemsihhatli.homecast.cast.MediaStatus
import io.github.yunuserdemsihhatli.homecast.cast.PlayerState
import io.github.yunuserdemsihhatli.homecast.cast.isEnded
import io.github.yunuserdemsihhatli.homecast.cast.mergeDevices
import io.github.yunuserdemsihhatli.homecast.playback.NowPlaying
import io.github.yunuserdemsihhatli.homecast.playback.PlaybackController
import io.github.yunuserdemsihhatli.homecast.playback.PlaybackError
import io.github.yunuserdemsihhatli.homecast.proxy.ProxiedSubtitle
import io.github.yunuserdemsihhatli.homecast.service.MediaSessionBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RemoteUiState(
    val connection: ConnectionState,
    val title: String?,
    val playerState: PlayerState?,
    val positionSeconds: Double,
    val durationSeconds: Double?,
    val isLive: Boolean,
    val volume: Double?,
    val muted: Boolean,
    val subtitleOptions: List<ProxiedSubtitle>,
    val activeSubtitleId: Int?,
    /** Something was playing but the TV no longer has it (dropped, ended, or the connection came back without it). */
    val canPlayAgain: Boolean = false,
)

/** Something to tell the user once (a snackbar). */
sealed interface RemoteMessage {
    /** A remote-control command (play, pause, seek, volume, ...) did not reach the TV. */
    data object CommandFailed : RemoteMessage

    data class Playback(
        val error: PlaybackError,
    ) : RemoteMessage
}

/**
 * State and actions of the remote screen and its device picker. Actions never throw: a failed command becomes a
 * [RemoteMessage], a failed connection shows as [ConnectionState.Failed].
 *
 * The receiver reports its position only when something changes, so while it is playing the position shown moves on
 * locally, by [clock] time since the last MEDIA_STATUS, re-evaluated on every [ticker] tick.
 *
 * Actions that change what plays or where (connect, stop, subtitles) run in [appScope], so leaving the screen right
 * after tapping them does not cancel them half-way; only state observation and transport commands are tied to the
 * screen's [viewModelScope].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModel(
    private val cast: CastRemote,
    private val playback: PlaybackController,
    private val deviceStore: DeviceStore,
    private val discovered: Flow<List<CastDevice>>,
    private val appScope: CoroutineScope,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    ticker: Flow<Unit> = ticks(TICK_MILLIS),
    discoveryTimeoutMillis: Long = DeviceConnector.DEFAULT_DISCOVERY_TIMEOUT_MILLIS,
) : ViewModel() {
    /** Follows a saved TV to a new address when its saved one no longer answers. */
    private val connector = DeviceConnector(cast, deviceStore, { discovered }, discoveryTimeoutMillis)

    /** The last media status and the [clock] time it arrived (or was predicted, after a seek). */
    private data class Anchor(
        val status: MediaStatus,
        val atMillis: Long,
    )

    private val anchor = MutableStateFlow<Anchor?>(null)

    private val failures =
        Channel<RemoteMessage>(capacity = MESSAGE_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        viewModelScope.launch { cast.media.collect { anchor.value = it?.let { Anchor(it, clock()) } } }
        // Subscribed from creation (not from the screen's first collect): a "Play on TV" started as the screen opens
        // can fail before the screen collects [messages], and playback.errors does not replay.
        viewModelScope.launch { playback.errors.collect { failures.trySend(RemoteMessage.Playback(it)) } }
    }

    /** One-off messages for the user, buffered until collected; collect from a single place. */
    val messages: Flow<RemoteMessage> = failures.receiveAsFlow()

    /** Saved devices, for telling which entries of [devices] can be forgotten. */
    val saved: StateFlow<List<CastDevice>> = deviceStore.saved

    /** Discovered and saved devices; discovery (and its multicast lock) runs only while this is collected. */
    val devices: StateFlow<List<CastDevice>> =
        combine(
            discovered
                .onStart { emit(emptyList()) }
                .catch {
                    Log.w(TAG, "Device discovery failed", it)
                    emit(emptyList())
                },
            deviceStore.saved,
            ::mergeDevices,
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARE_TIMEOUT_MILLIS), deviceStore.saved.value)

    /** Ticks only while playing: nothing moves otherwise. */
    private val positionTicks: Flow<Unit> =
        anchor.flatMapLatest { a ->
            if (a?.status?.playerState == PlayerState.PLAYING) ticker.onStart { emit(Unit) } else flowOf(Unit)
        }

    val state: StateFlow<RemoteUiState> =
        combine(
            cast.connection,
            anchor,
            cast.volume,
            playback.nowPlaying,
            positionTicks,
        ) { connection, a, volume, np, _ ->
            uiState(connection, a, volume?.level, volume?.muted ?: false, np)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(SHARE_TIMEOUT_MILLIS),
            uiState(
                cast.connection.value,
                anchor.value,
                cast.volume.value?.level,
                cast.volume.value?.muted ?: false,
                playback.nowPlaying.value,
            ),
        )

    fun togglePlay() {
        val status = anchor.value?.status ?: return
        command {
            if (status.playerState == PlayerState.PLAYING || status.playerState == PlayerState.BUFFERING) {
                cast.pause()
            } else {
                cast.play()
            }
        }
    }

    /** Seeks to [seconds], kept within 0..duration. No-op for live streams or when nothing is loaded. */
    fun seekTo(seconds: Double) {
        val current = anchor.value ?: return
        if (isLive()) return
        val target = seconds.coerceIn(0.0, current.status.durationSeconds ?: Double.MAX_VALUE)
        // Show the new position right away; the receiver's next MEDIA_STATUS replaces this prediction.
        val predicted = Anchor(current.status.copy(currentTimeSeconds = target), clock())
        anchor.value = predicted
        command(onFailure = { anchor.compareAndSet(predicted, current) }) { cast.seek(target) }
    }

    /** Jumps [deltaSeconds] from the current (interpolated) position. No-op for live streams. */
    fun skip(deltaSeconds: Int) {
        val current = anchor.value ?: return
        if (isLive()) return
        seekTo(
            MediaSessionBridge.skipTargetSeconds(current.status, clock() - current.atMillis, deltaSeconds.toDouble()),
        )
    }

    fun setVolume(level: Double) {
        val clamped = level.coerceIn(0.0, 1.0)
        command { cast.setVolume(clamped) }
    }

    fun toggleMute() {
        val muted = cast.volume.value?.muted ?: false
        command { cast.setMuted(!muted) }
    }

    /** Switches subtitles to [trackId]; null turns them off. */
    fun selectSubtitle(trackId: Int?) {
        background("Selecting subtitles failed") { playback.selectSubtitle(trackId) }
    }

    /** Adds a subtitle file the user picked; [displayName] becomes its label. */
    fun addSubtitle(
        bytes: ByteArray,
        displayName: String,
    ) {
        background("Adding subtitles failed") { playback.addSubtitleFile(bytes, displayName) }
    }

    /** LOADs what was playing again, from where it was; see [PlaybackController.playAgain]. */
    fun playAgain() {
        background("Play again failed") { playback.playAgain() }
    }

    fun stop() {
        background("Stop failed") { playback.stop() }
    }

    /**
     * Remembers [device] as the last used one and connects to it, looking for it at a new address (and saving that) if
     * it does not answer at the saved one; a failure shows as [ConnectionState.Failed].
     */
    fun connect(device: CastDevice) {
        deviceStore.save(device)
        // The banner shows the failure (ConnectionState.Failed); the log keeps the detail.
        background("Couldn't connect to ${device.host}:${device.port}", reportFailure = false) {
            connector.connect(device)
        }
    }

    /** Connects to the device that failed or, when disconnected, the last used one. False when there is none. */
    fun reconnect(): Boolean {
        val device =
            when (val c = cast.connection.value) {
                is ConnectionState.Failed -> c.device
                is ConnectionState.Connected, is ConnectionState.Connecting -> return true
                ConnectionState.Disconnected -> deviceStore.lastUsed()
            } ?: return false
        connect(device)
        return true
    }

    /**
     * Connects to a device typed in as `a.b.c.d` or `a.b.c.d:port` (port 8009 by default), saving it as
     * "TV (a.b.c.d)". Returns false, without connecting, when [input] is not such an address.
     */
    fun connectByIp(input: String): Boolean {
        val (host, port) = parseIpv4Endpoint(input) ?: return false
        // A stored identifier, not a UI label: it must stay the same whatever the phone's language.
        connect(CastDevice(id = null, name = "TV ($host)", host = host, port = port))
        return true
    }

    fun forget(device: CastDevice) = deviceStore.remove(device)

    private fun isLive(): Boolean =
        playback.nowPlaying.value
            ?.session
            ?.isLive == true

    /** Runs [block] in [appScope]; a failure is logged and, when [reportFailure], shown as [RemoteMessage.CommandFailed]. */
    private fun background(
        logMessage: String,
        reportFailure: Boolean = true,
        block: suspend () -> Unit,
    ) {
        appScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, logMessage, e)
                if (reportFailure) failures.trySend(RemoteMessage.CommandFailed)
            }
        }
    }

    private fun command(
        onFailure: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Remote command failed", e)
                onFailure()
                failures.trySend(RemoteMessage.CommandFailed)
            }
        }
    }

    private fun uiState(
        connection: ConnectionState,
        a: Anchor?,
        volume: Double?,
        muted: Boolean,
        nowPlaying: NowPlaying?,
    ): RemoteUiState {
        val status = a?.status
        val duration = status?.durationSeconds
        val subtitles = nowPlaying?.session?.subtitles.orEmpty()
        val position =
            if (a == null) {
                0.0
            } else {
                val seconds = MediaSessionBridge.estimatedPositionMs(a.status, clock() - a.atMillis) / 1000.0
                seconds.coerceIn(0.0, duration ?: Double.MAX_VALUE)
            }
        return RemoteUiState(
            connection = connection,
            title = nowPlaying?.title,
            playerState = status?.playerState,
            positionSeconds = position,
            durationSeconds = duration,
            isLive = nowPlaying?.session?.isLive == true,
            volume = volume,
            muted = muted,
            subtitleOptions = subtitles,
            // Only a track the remote can name; anything else (e.g. an embedded track) shows as "Off".
            activeSubtitleId = status?.activeTrackIds?.firstOrNull { id -> subtitles.any { it.trackId == id } },
            canPlayAgain =
                nowPlaying != null &&
                    (status == null || status.isEnded) &&
                    connection !is ConnectionState.Connecting,
        )
    }

    companion object {
        private const val TAG = "RemoteViewModel"
        private const val TICK_MILLIS = 500L
        private const val SHARE_TIMEOUT_MILLIS = 5_000L
        private const val MESSAGE_BUFFER = 8
        private const val DEFAULT_CAST_PORT = 8009
        private val IPV4_ENDPOINT = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?::(\d{1,5}))?""")

        /** `a.b.c.d` (each 0..255) with an optional `:port` (1..65535) as a normalised host and port. */
        internal fun parseIpv4Endpoint(input: String): Pair<String, Int>? {
            val match = IPV4_ENDPOINT.matchEntire(input.trim()) ?: return null
            val octets = match.groupValues.subList(1, 5).map { it.toInt() }
            if (octets.any { it > 255 }) return null
            val portText = match.groups[5]?.value
            val port = portText?.toInt() ?: DEFAULT_CAST_PORT
            if (port !in 1..65_535) return null
            return octets.joinToString(".") to port
        }

        private fun ticks(periodMillis: Long): Flow<Unit> =
            flow {
                while (true) {
                    delay(periodMillis)
                    emit(Unit)
                }
            }

        val Factory: ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val graph = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as HomeCastApp).graph
                    RemoteViewModel(
                        graph.cast,
                        graph.playback,
                        graph.deviceStore,
                        graph.discovery.devices(),
                        graph.appScope,
                    )
                }
            }
    }
}
