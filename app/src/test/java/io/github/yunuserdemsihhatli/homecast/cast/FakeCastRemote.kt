package io.github.yunuserdemsihhatli.homecast.cast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory [CastRemote] for tests of code that drives a Cast session. Records every call in
 * [calls], exposes its state as settable [MutableStateFlow]s, and simulates a cooperative receiver:
 * `connect` → Connected, `load` → a PLAYING [MediaStatus], `pause`/`play`/`seek`/`stopMedia`/
 * `setActiveTracks` update [media], volume calls update [volume], `disconnect` → Disconnected.
 * Set [connectFailure], [loadFailure] or [commandFailure] to make those calls throw instead, or [loadGate] to
 * make `load` hang.
 * Like [CastClient], every call except connect/disconnect throws [CastException] unless
 * [connection] is Connected, and media commands also throw when [media] is null (calls are still
 * recorded first).
 */
class FakeCastRemote : CastRemote {
    sealed interface Call {
        data class Connect(
            val device: CastDevice,
        ) : Call

        data class Load(
            val request: LoadRequest,
        ) : Call

        data object Play : Call

        data object Pause : Call

        data class Seek(
            val seconds: Double,
        ) : Call

        data object StopMedia : Call

        data class SetVolume(
            val level: Double,
        ) : Call

        data class SetMuted(
            val muted: Boolean,
        ) : Call

        data class SetActiveTracks(
            val trackIds: List<Int>,
        ) : Call

        data object Disconnect : Call
    }

    override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val media = MutableStateFlow<MediaStatus?>(null)
    override val volume = MutableStateFlow<VolumeState?>(null)

    private val recorded = mutableListOf<Call>()

    /** Every call so far, in order (snapshot). */
    val calls: List<Call> get() = synchronized(recorded) { recorded.toList() }

    /** The requests passed to [load], in order (snapshot). */
    val loadRequests: List<LoadRequest> get() = calls.filterIsInstance<Call.Load>().map { it.request }

    /** Thrown by [connect] after setting [connection] to [ConnectionState.Failed]. */
    @Volatile var connectFailure: Exception? = null

    /** Per-device connect failure, checked after [connectFailure]; null means that device connects. */
    @Volatile var connectFailureFor: (CastDevice) -> Exception? = { null }

    /** Thrown by [load]. */
    @Volatile var loadFailure: Exception? = null

    /** When set, [load] (after recording the call) suspends until it completes, to simulate a slow receiver. */
    @Volatile var loadGate: CompletableDeferred<Unit>? = null

    /** Thrown by play, pause, seek, stopMedia, setVolume, setMuted and setActiveTracks. */
    @Volatile var commandFailure: Exception? = null

    private val nextMediaSessionId = AtomicInteger(1)

    private fun record(call: Call) = synchronized(recorded) { recorded += call }

    override suspend fun connect(device: CastDevice) {
        record(Call.Connect(device))
        (connectFailure ?: connectFailureFor(device))?.let {
            connection.value = ConnectionState.Failed(device, it.message ?: "connect failed")
            throw it
        }
        connection.value = ConnectionState.Connected(device)
        if (volume.value == null) volume.value = VolumeState(0.5, false)
    }

    override suspend fun load(request: LoadRequest) {
        record(Call.Load(request))
        loadGate?.await()
        requireConnected()
        loadFailure?.let { throw it }
        media.value =
            MediaStatus(
                mediaSessionId = nextMediaSessionId.getAndIncrement(),
                playerState = PlayerState.PLAYING,
                currentTimeSeconds = if (request.streamType == StreamType.LIVE) 0.0 else request.startTimeSeconds,
                durationSeconds = null,
                idleReason = null,
                activeTrackIds = request.activeTrackIds,
            )
    }

    override suspend fun play() = command(Call.Play) { copy(playerState = PlayerState.PLAYING) }

    override suspend fun pause() = command(Call.Pause) { copy(playerState = PlayerState.PAUSED) }

    override suspend fun seek(seconds: Double) = command(Call.Seek(seconds)) { copy(currentTimeSeconds = seconds) }

    override suspend fun stopMedia() = command(Call.StopMedia) { null }

    override suspend fun setActiveTracks(trackIds: List<Int>) =
        command(Call.SetActiveTracks(trackIds)) {
            copy(activeTrackIds = trackIds)
        }

    override suspend fun setVolume(level: Double) {
        record(Call.SetVolume(level))
        requireConnected()
        commandFailure?.let { throw it }
        volume.value = VolumeState(level, volume.value?.muted ?: false)
    }

    override suspend fun setMuted(muted: Boolean) {
        record(Call.SetMuted(muted))
        requireConnected()
        commandFailure?.let { throw it }
        volume.value = VolumeState(volume.value?.level ?: 0.5, muted)
    }

    override suspend fun disconnect() {
        record(Call.Disconnect)
        connection.value = ConnectionState.Disconnected
        media.value = null
        volume.value = null
    }

    private fun command(
        call: Call,
        update: MediaStatus.() -> MediaStatus?,
    ) {
        record(call)
        requireConnected()
        val current = media.value ?: throw CastException("No media is loaded")
        commandFailure?.let { throw it }
        media.value = current.update()
    }

    private fun requireConnected() {
        if (connection.value !is ConnectionState.Connected) throw CastException("Not connected to a Cast device")
    }
}
