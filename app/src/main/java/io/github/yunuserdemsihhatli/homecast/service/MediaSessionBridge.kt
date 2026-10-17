// androidx.media (MediaSessionCompat & co.) is deprecated in favour of media3-session, which is overkill here.
@file:Suppress("DEPRECATION")

package io.github.yunuserdemsihhatli.homecast.service

import android.support.v4.media.session.PlaybackStateCompat
import io.github.yunuserdemsihhatli.homecast.cast.MediaStatus
import io.github.yunuserdemsihhatli.homecast.cast.PlayerState
import kotlin.math.roundToInt

/** What the media session should show: a `PlaybackStateCompat.STATE_*`, a position and `ACTION_*` flags. */
data class SessionPlayback(
    val state: Int,
    val positionMs: Long,
    val actions: Long,
)

/**
 * Pure mapping from the receiver's [MediaStatus] to media-session values. Uses only the compile-time constants of
 * [PlaybackStateCompat], so it runs on the plain JVM.
 */
object MediaSessionBridge {
    private const val BASE_ACTIONS =
        PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP
    private const val SEEK_ACTIONS =
        PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_FAST_FORWARD or
            PlaybackStateCompat.ACTION_REWIND
    private const val IDLE_REASON_ERROR = "ERROR"

    /** Custom session actions; Android 13+ builds media controls from these, not from notification actions. */
    const val CUSTOM_REWIND = "io.github.yunuserdemsihhatli.homecast.REWIND_10"
    const val CUSTOM_FORWARD = "io.github.yunuserdemsihhatli.homecast.FORWARD_10"
    const val CUSTOM_STOP = "io.github.yunuserdemsihhatli.homecast.STOP"

    /** Hardware volume keys move the TV volume in this many steps. */
    const val VOLUME_STEPS = 20

    /** [live] streams cannot be seeked, so they get no seek / fast-forward / rewind actions. */
    fun toPlaybackState(
        status: MediaStatus?,
        live: Boolean,
    ): SessionPlayback {
        if (status == null) return SessionPlayback(PlaybackStateCompat.STATE_NONE, 0L, PlaybackStateCompat.ACTION_STOP)
        val state =
            when (status.playerState) {
                PlayerState.PLAYING -> {
                    PlaybackStateCompat.STATE_PLAYING
                }

                PlayerState.PAUSED -> {
                    PlaybackStateCompat.STATE_PAUSED
                }

                PlayerState.BUFFERING -> {
                    PlaybackStateCompat.STATE_BUFFERING
                }

                PlayerState.IDLE -> {
                    if (status.idleReason == IDLE_REASON_ERROR) {
                        PlaybackStateCompat.STATE_ERROR
                    } else {
                        PlaybackStateCompat.STATE_STOPPED
                    }
                }

                PlayerState.UNKNOWN -> {
                    PlaybackStateCompat.STATE_NONE
                }
            }
        val actions = if (live) BASE_ACTIONS else BASE_ACTIONS or SEEK_ACTIONS
        return SessionPlayback(state, (status.currentTimeSeconds * 1000).toLong(), actions)
    }

    /**
     * The receiver only reports its position when something changes, so while it is playing the position moves on
     * by the time passed since that report ([elapsedSinceStatusMs]).
     */
    fun estimatedPositionMs(
        status: MediaStatus?,
        elapsedSinceStatusMs: Long,
    ): Long {
        if (status == null) return 0L
        val reported = (status.currentTimeSeconds * 1000).toLong()
        return if (status.playerState == PlayerState.PLAYING) reported + elapsedSinceStatusMs else reported
    }

    /** Where a skip of [deltaSeconds] from the estimated position lands, kept within 0..duration (when known). */
    fun skipTargetSeconds(
        status: MediaStatus,
        elapsedSinceStatusMs: Long,
        deltaSeconds: Double,
    ): Double {
        val target = estimatedPositionMs(status, elapsedSinceStatusMs) / 1000.0 + deltaSeconds
        val upper = status.durationSeconds ?: Double.MAX_VALUE
        return target.coerceIn(0.0, upper)
    }

    /** Receiver volume 0.0..1.0 (null when unknown) as a volume-key step 0..[VOLUME_STEPS]. */
    fun volumeToSteps(level: Double?): Int = ((level ?: 0.0) * VOLUME_STEPS).roundToInt().coerceIn(0, VOLUME_STEPS)

    /** A volume-key step as a receiver volume 0.0..1.0. */
    fun stepsToVolume(steps: Int): Double = steps.coerceIn(0, VOLUME_STEPS).toDouble() / VOLUME_STEPS

    /** Custom action ids to publish, in display order: ±10 s skips only when the media can be seeked, Stop always. */
    fun customActions(live: Boolean): List<String> =
        if (live) listOf(CUSTOM_STOP) else listOf(CUSTOM_REWIND, CUSTOM_FORWARD, CUSTOM_STOP)
}
