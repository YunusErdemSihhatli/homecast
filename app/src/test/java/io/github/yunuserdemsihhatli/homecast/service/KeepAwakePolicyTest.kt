package io.github.yunuserdemsihhatli.homecast.service

import io.github.yunuserdemsihhatli.homecast.cast.CastDevice
import io.github.yunuserdemsihhatli.homecast.cast.ConnectionState
import io.github.yunuserdemsihhatli.homecast.cast.MediaStatus
import io.github.yunuserdemsihhatli.homecast.cast.PlayerState
import io.github.yunuserdemsihhatli.homecast.service.KeepAwakePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class KeepAwakePolicyTest {
    private var now = 1_000L
    private val policy = KeepAwakePolicy(clock = { now })
    private val tv = CastDevice(id = "tv", name = "TV", host = "192.168.1.20")
    private val connected = ConnectionState.Connected(tv)
    private val failed = ConnectionState.Failed(tv, "gone")

    private fun media(
        state: PlayerState,
        idleReason: String? = null,
    ) = MediaStatus(1, state, 10.0, 100.0, idleReason, emptyList())

    @Test fun awakeWhilePlayingBufferingOrPaused() {
        for (state in listOf(PlayerState.PLAYING, PlayerState.BUFFERING, PlayerState.PAUSED)) {
            assertEquals(state.name, Decision.Awake(), policy.evaluate(media(state), connected, starting = false))
        }
    }

    @Test fun idleWithoutMediaOrWhenItEnded() {
        assertEquals(Decision.Idle, policy.evaluate(null, connected, starting = false))
        assertEquals(Decision.Idle, policy.evaluate(null, ConnectionState.Disconnected, starting = false))
        for (reason in listOf("FINISHED", "ERROR", "CANCELLED")) {
            assertEquals(reason, Decision.Idle, policy.evaluate(media(PlayerState.IDLE, reason), connected, false))
        }
    }

    @Test fun idleWhileLoadingOrInterruptedStaysAwake() {
        assertEquals(Decision.Awake(), policy.evaluate(media(PlayerState.IDLE), connected, starting = false))
        assertEquals(
            Decision.Awake(),
            policy.evaluate(media(PlayerState.IDLE, "INTERRUPTED"), connected, starting = false),
        )
    }

    @Test fun startingAlwaysKeepsAwake() {
        assertEquals(Decision.Awake(), policy.evaluate(null, ConnectionState.Disconnected, starting = true))
    }

    @Test fun reconnectingKeepsAwake() {
        assertEquals(Decision.Awake(), policy.evaluate(null, ConnectionState.Connecting(tv), starting = false))
    }

    @Test fun failedConnectionKeepsAwakeForTwoMinutesThenGoesIdle() {
        assertEquals(Decision.Awake(recheckInMillis = 120_000), policy.evaluate(null, failed, starting = false))
        now += 90_000
        assertEquals(Decision.Awake(recheckInMillis = 30_000), policy.evaluate(null, failed, starting = false))
        now += 30_000
        assertEquals(Decision.Idle, policy.evaluate(null, failed, starting = false))
    }

    @Test fun graceRestartsAfterTheConnectionRecovered() {
        policy.evaluate(null, failed, starting = false)
        now += 119_000
        assertEquals(Decision.Awake(), policy.evaluate(media(PlayerState.PLAYING), connected, starting = false))
        now += 5_000
        assertEquals(Decision.Awake(recheckInMillis = 120_000), policy.evaluate(null, failed, starting = false))
    }

    @Test fun reacquiresWhenPlaybackResumesAfterIdle() {
        assertEquals(Decision.Idle, policy.evaluate(null, connected, starting = false))
        assertEquals(Decision.Awake(), policy.evaluate(media(PlayerState.BUFFERING), connected, starting = false))
    }
}
