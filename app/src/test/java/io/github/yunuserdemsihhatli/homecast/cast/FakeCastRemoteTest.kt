package io.github.yunuserdemsihhatli.homecast.cast

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FakeCastRemoteTest {
    private val device = CastDevice(id = null, name = "TV", host = "192.168.1.5")
    private val request =
        LoadRequest("http://x/v.mp4", "video/mp4", StreamType.BUFFERED, "Video", emptyList(), emptyList(), 5.0, null)

    private fun throwsCastException(block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            throw AssertionError("expected CastException")
        } catch (_: CastException) {
        }
    }

    @Test fun commandsFailLikeTheRealClientWhenNotConnectedOrNothingLoaded() {
        val remote = FakeCastRemote()
        throwsCastException { remote.load(request) }
        throwsCastException { remote.setVolume(0.3) }
        runBlocking { remote.connect(device) }
        throwsCastException { remote.pause() }
        assertNull(remote.media.value)
        assertEquals(3, remote.calls.size - 1) // load, setVolume, pause recorded besides connect
    }

    @Test fun simulatesReceiver() =
        runBlocking {
            val remote = FakeCastRemote()
            remote.connect(device)
            remote.load(request)
            assertEquals(MediaStatus(1, PlayerState.PLAYING, 5.0, null, null, emptyList()), remote.media.value)
            remote.pause()
            assertEquals(PlayerState.PAUSED, remote.media.value?.playerState)
            remote.load(request)
            assertEquals(2, remote.media.value?.mediaSessionId)
            assertEquals(listOf(request, request), remote.loadRequests)
            remote.disconnect()
            assertEquals(ConnectionState.Disconnected, remote.connection.value)
        }
}
