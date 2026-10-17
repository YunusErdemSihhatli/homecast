// androidx.media (MediaSessionCompat & co.) is deprecated in favour of media3-session, which is overkill here.
@file:Suppress("DEPRECATION")

package io.github.yunuserdemsihhatli.homecast.service

import android.support.v4.media.session.PlaybackStateCompat
import io.github.yunuserdemsihhatli.homecast.cast.MediaStatus
import io.github.yunuserdemsihhatli.homecast.cast.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionBridgeTest {
    private fun status(
        state: PlayerState,
        time: Double = 0.0,
        idleReason: String? = null,
    ) = MediaStatus(
        mediaSessionId = 1,
        playerState = state,
        currentTimeSeconds = time,
        durationSeconds = 600.0,
        idleReason = idleReason,
        activeTrackIds = emptyList(),
    )

    private fun Long.has(action: Long) = this and action != 0L

    @Test
    fun playingMapsToStatePlayingWithPosition() {
        val result = MediaSessionBridge.toPlaybackState(status(PlayerState.PLAYING, time = 12.5), live = false)

        assertEquals(PlaybackStateCompat.STATE_PLAYING, result.state)
        assertEquals(12_500L, result.positionMs)
        assertTrue(result.actions.has(PlaybackStateCompat.ACTION_PAUSE))
        assertTrue(result.actions.has(PlaybackStateCompat.ACTION_SEEK_TO))
        assertTrue(result.actions.has(PlaybackStateCompat.ACTION_FAST_FORWARD))
        assertTrue(result.actions.has(PlaybackStateCompat.ACTION_REWIND))
        assertTrue(result.actions.has(PlaybackStateCompat.ACTION_STOP))
    }

    @Test
    fun pausedMapsToStatePaused() {
        val result = MediaSessionBridge.toPlaybackState(status(PlayerState.PAUSED, time = 3.0), live = false)

        assertEquals(PlaybackStateCompat.STATE_PAUSED, result.state)
        assertEquals(3_000L, result.positionMs)
        assertTrue(result.actions.has(PlaybackStateCompat.ACTION_PLAY))
    }

    @Test
    fun bufferingMapsToStateBuffering() {
        val result = MediaSessionBridge.toPlaybackState(status(PlayerState.BUFFERING, time = 1.0), live = false)

        assertEquals(PlaybackStateCompat.STATE_BUFFERING, result.state)
    }

    @Test
    fun idleWithFinishedReasonMapsToStopped() {
        val result = MediaSessionBridge.toPlaybackState(status(PlayerState.IDLE, idleReason = "FINISHED"), live = false)

        assertEquals(PlaybackStateCompat.STATE_STOPPED, result.state)
    }

    @Test
    fun idleWithErrorReasonMapsToError() {
        val result = MediaSessionBridge.toPlaybackState(status(PlayerState.IDLE, idleReason = "ERROR"), live = false)

        assertEquals(PlaybackStateCompat.STATE_ERROR, result.state)
    }

    @Test
    fun liveHidesSeekActions() {
        val result = MediaSessionBridge.toPlaybackState(status(PlayerState.PLAYING, time = 40.0), live = true)

        assertEquals(PlaybackStateCompat.STATE_PLAYING, result.state)
        assertTrue(result.actions.has(PlaybackStateCompat.ACTION_PAUSE))
        assertTrue(result.actions.has(PlaybackStateCompat.ACTION_STOP))
        assertEquals(0L, result.actions and PlaybackStateCompat.ACTION_SEEK_TO)
        assertEquals(0L, result.actions and PlaybackStateCompat.ACTION_FAST_FORWARD)
        assertEquals(0L, result.actions and PlaybackStateCompat.ACTION_REWIND)
    }

    @Test
    fun nullStatusMapsToNone() {
        val result = MediaSessionBridge.toPlaybackState(null, live = false)

        assertEquals(PlaybackStateCompat.STATE_NONE, result.state)
        assertEquals(0L, result.positionMs)
    }

    @Test
    fun estimatedPositionAdvancesOnlyWhilePlaying() {
        assertEquals(15_000L, MediaSessionBridge.estimatedPositionMs(status(PlayerState.PLAYING, 10.0), 5_000L))
        assertEquals(10_000L, MediaSessionBridge.estimatedPositionMs(status(PlayerState.PAUSED, 10.0), 5_000L))
        assertEquals(0L, MediaSessionBridge.estimatedPositionMs(null, 5_000L))
    }

    @Test
    fun skipTargetIsClampedToMediaBounds() {
        val playing = status(PlayerState.PAUSED, time = 595.0)

        assertEquals(600.0, MediaSessionBridge.skipTargetSeconds(playing, 0L, +10.0), 0.0)
        assertEquals(0.0, MediaSessionBridge.skipTargetSeconds(status(PlayerState.PAUSED, 4.0), 0L, -10.0), 0.0)
        assertEquals(30.0, MediaSessionBridge.skipTargetSeconds(status(PlayerState.PLAYING, 18.0), 2_000L, +10.0), 0.0)
    }

    @Test
    fun volumeLevelMapsToTwentySteps() {
        assertEquals(0, MediaSessionBridge.volumeToSteps(null))
        assertEquals(0, MediaSessionBridge.volumeToSteps(0.0))
        assertEquals(10, MediaSessionBridge.volumeToSteps(0.5))
        assertEquals(7, MediaSessionBridge.volumeToSteps(0.34))
        assertEquals(20, MediaSessionBridge.volumeToSteps(1.0))
        assertEquals(20, MediaSessionBridge.volumeToSteps(1.7))
    }

    @Test
    fun stepsMapBackToVolumeLevelClamped() {
        assertEquals(0.25, MediaSessionBridge.stepsToVolume(5), 1e-9)
        assertEquals(1.0, MediaSessionBridge.stepsToVolume(25), 1e-9)
        assertEquals(0.0, MediaSessionBridge.stepsToVolume(-1), 1e-9)
    }

    @Test
    fun customActionsOfferSkipsAndStopForSeekableMedia() {
        assertEquals(
            listOf(MediaSessionBridge.CUSTOM_REWIND, MediaSessionBridge.CUSTOM_FORWARD, MediaSessionBridge.CUSTOM_STOP),
            MediaSessionBridge.customActions(live = false),
        )
    }

    @Test
    fun customActionsOfferOnlyStopForLive() {
        assertEquals(listOf(MediaSessionBridge.CUSTOM_STOP), MediaSessionBridge.customActions(live = true))
    }
}
