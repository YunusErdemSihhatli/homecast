package io.github.yunuserdemsihhatli.homecast.cast

import io.github.yunuserdemsihhatli.homecast.proxy.ProxiedSubtitle
import io.github.yunuserdemsihhatli.homecast.proxy.SegmentFormat
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

enum class StreamType { BUFFERED, LIVE }

enum class PlayerState { IDLE, BUFFERING, PLAYING, PAUSED, UNKNOWN }

data class LoadRequest(
    val url: String,
    val contentType: String,
    val streamType: StreamType,
    val title: String,
    val subtitles: List<ProxiedSubtitle>,
    val activeTrackIds: List<Int>,
    val startTimeSeconds: Double,
    val hlsSegmentFormat: SegmentFormat?,
)

data class MediaStatus(
    val mediaSessionId: Int,
    val playerState: PlayerState,
    val currentTimeSeconds: Double,
    val durationSeconds: Double?,
    val idleReason: String?,
    val activeTrackIds: List<Int>,
)

/** The receiver has nothing to play any more: it went IDLE because the media finished, failed or was stopped. */
val MediaStatus.isEnded: Boolean
    get() = playerState == PlayerState.IDLE && idleReason in ENDED_IDLE_REASONS

private val ENDED_IDLE_REASONS = setOf("FINISHED", "ERROR", "CANCELLED")

data class VolumeState(
    val level: Double,
    val muted: Boolean,
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    data class Connecting(
        val device: CastDevice,
    ) : ConnectionState

    data class Connected(
        val device: CastDevice,
    ) : ConnectionState

    data class Failed(
        val device: CastDevice,
        val message: String,
    ) : ConnectionState
}

/** A Cast operation failed: not connected, no media session, connection lost, ... */
open class CastException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Opening the connection to [device] failed (TV off, wrong address, not on this network, ...). */
class CastConnectException(
    val device: CastDevice,
    cause: Throwable,
) : CastException("Could not connect to ${device.name} at ${device.host}:${device.port}", cause)

/** The receiver did not answer a request in time. */
class CastTimeoutException(
    message: String,
) : CastException(message)

/** The receiver rejected a request, e.g. `LOAD_FAILED`, `LOAD_CANCELLED`, `INVALID_REQUEST`. */
class CastRequestException(
    val type: String,
    val reason: String?,
) : CastException(if (reason == null) type else "$type ($reason)")

/**
 * Sender side of a Cast session with the Default Media Receiver. Commands throw [CastException] on
 * failure (including [CastRequestException] for receiver error replies); [connect] throws
 * [CastConnectException]. No other exception type escapes, except cancellation.
 */
interface CastRemote {
    val connection: StateFlow<ConnectionState>

    /** Last known media status; null when nothing is loaded or the receiver app went away. */
    val media: StateFlow<MediaStatus?>
    val volume: StateFlow<VolumeState?>

    /**
     * CONNECT receiver-0, LAUNCH the Default Media Receiver (or reuse the running one), CONNECT its
     * transport. On failure [connection] becomes [ConnectionState.Failed] and the error is thrown.
     */
    suspend fun connect(device: CastDevice)

    /** Loads [request]; relaunches the receiver app first if it was closed on the TV. */
    suspend fun load(request: LoadRequest)

    suspend fun play()

    suspend fun pause()

    suspend fun seek(seconds: Double)

    suspend fun stopMedia()

    suspend fun setVolume(level: Double)

    suspend fun setMuted(muted: Boolean)

    /** EDIT_TRACKS_INFO; an empty list turns subtitles off. */
    suspend fun setActiveTracks(trackIds: List<Int>)

    /** CLOSE and socket close; does not stop the receiver app. */
    suspend fun disconnect()
}
