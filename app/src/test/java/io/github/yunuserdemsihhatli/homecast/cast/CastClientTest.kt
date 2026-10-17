package io.github.yunuserdemsihhatli.homecast.cast

import io.github.yunuserdemsihhatli.homecast.cast.FakeDefaultMediaReceiver.Companion.CONNECTION
import io.github.yunuserdemsihhatli.homecast.cast.FakeDefaultMediaReceiver.Companion.HEARTBEAT
import io.github.yunuserdemsihhatli.homecast.cast.FakeDefaultMediaReceiver.Companion.MEDIA
import io.github.yunuserdemsihhatli.homecast.cast.FakeDefaultMediaReceiver.Companion.RECEIVER
import io.github.yunuserdemsihhatli.homecast.cast.protocol.CastChannel
import io.github.yunuserdemsihhatli.homecast.cast.protocol.CastMessage
import io.github.yunuserdemsihhatli.homecast.proxy.ProxiedSubtitle
import io.github.yunuserdemsihhatli.homecast.proxy.SegmentFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CastClientTest {
    private val receiver = FakeCastReceiver()
    private val dmr = FakeDefaultMediaReceiver()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channels = CopyOnWriteArrayList<CastChannel>()
    private val channelsCreated get() = channels.size
    private lateinit var device: CastDevice
    private var client: CastClient? = null

    @Before fun setUp() {
        receiver.autoRespond = dmr::respond
        device = CastDevice(id = "tv-1", name = "Living room TV", host = "127.0.0.1", port = receiver.start())
    }

    @After fun tearDown() {
        runBlocking { withTimeout(5_000) { client?.disconnect() } }
        scope.cancel()
        receiver.close()
    }

    private fun newClient(
        heartbeatTimeoutMillis: Long = 5_000,
        requestTimeoutMillis: Long = 2_000,
    ) = CastClient(
        scope = scope,
        channelFactory = { CastChannel(it.host, it.port).also(channels::add) },
        heartbeatIntervalMillis = 100,
        heartbeatTimeoutMillis = heartbeatTimeoutMillis,
        requestTimeoutMillis = requestTimeoutMillis,
        reconnectBackoffMillis = listOf(50, 100, 200),
    ).also { client = it }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    private class Received(
        val message: CastMessage,
        val json: JsonObject,
    ) {
        val type get() = json["type"]!!.jsonPrimitive.content
    }

    /** Next message the receiver got outside the heartbeat namespace. */
    private suspend fun nextNonHeartbeat(): Received {
        while (true) {
            val m = receiver.received.receive()
            if (m.namespace != HEARTBEAT) return Received(m, Json.parseToJsonElement(m.payloadUtf8).jsonObject)
        }
    }

    private suspend fun next(
        namespace: String,
        type: String,
    ): Received {
        while (true) {
            val m = receiver.received.receive()
            val json = Json.parseToJsonElement(m.payloadUtf8).jsonObject
            if (m.namespace == namespace && json["type"]?.jsonPrimitive?.content == type) return Received(m, json)
        }
    }

    private suspend fun pushMediaStatus(
        sessionId: Int,
        state: String,
        time: Double,
    ) {
        dmr.mediaSessionId = sessionId
        dmr.playerState = state
        dmr.currentTime = time
        receiver.send(CastMessage("t1", "*", MEDIA, dmr.mediaStatus().toString()))
        client!!.media.first { it?.mediaSessionId == sessionId && it.currentTimeSeconds == time }
    }

    private fun hlsRequest(
        streamType: StreamType = StreamType.BUFFERED,
        subtitles: List<ProxiedSubtitle> = emptyList(),
        format: SegmentFormat? = null,
    ) = LoadRequest(
        url = "http://192.168.1.20:8080/p/abc/master.m3u8",
        contentType = "application/x-mpegURL",
        streamType = streamType,
        title = "Episode 1",
        subtitles = subtitles,
        activeTrackIds = subtitles.map { it.trackId },
        startTimeSeconds = 42.0,
        hlsSegmentFormat = format,
    )

    @Test fun connectLaunchesDefaultReceiverAndConnectsToTransport() =
        test {
            val c = newClient()
            c.connect(device)

            val connect = nextNonHeartbeat()
            assertEquals(CONNECTION, connect.message.namespace)
            assertEquals("receiver-0", connect.message.destinationId)
            assertEquals("sender-0", connect.message.sourceId)
            assertEquals("CONNECT", connect.type)
            assertEquals("GET_STATUS", nextNonHeartbeat().type)
            val launch = nextNonHeartbeat()
            assertEquals(RECEIVER, launch.message.namespace)
            assertEquals("LAUNCH", launch.type)
            assertEquals("CC1AD845", launch.json["appId"]!!.jsonPrimitive.content)
            val transport = nextNonHeartbeat()
            assertEquals("CONNECT", transport.type)
            assertEquals(CONNECTION, transport.message.namespace)
            assertEquals("t1", transport.message.destinationId)
            assertEquals(ConnectionState.Connected(device), c.connection.value)
            assertEquals(VolumeState(0.5, false), c.volume.value)
        }

    @Test fun connectReusesRunningDefaultReceiver() =
        test {
            dmr.appRunning = true
            val c = newClient()
            c.connect(device)

            val seen = mutableListOf<String>()
            while (true) {
                val r = nextNonHeartbeat()
                seen += r.type
                if (r.message.namespace == MEDIA && r.type == "GET_STATUS") break
            }
            assertFalse("LAUNCH sent although the receiver app was running: $seen", "LAUNCH" in seen)
            assertEquals(listOf("CONNECT", "GET_STATUS", "CONNECT", "GET_STATUS"), seen)
            assertEquals(ConnectionState.Connected(device), c.connection.value)
        }

    @Test fun loadSendsExpectedJson() =
        test {
            val c = newClient()
            c.connect(device)
            val subtitle = ProxiedSubtitle(1, "http://192.168.1.20:8080/s/1.vtt", "Türkçe", "tr")
            c.load(hlsRequest(subtitles = listOf(subtitle), format = SegmentFormat.TS))

            val load = next(MEDIA, "LOAD")
            assertEquals("t1", load.message.destinationId)
            val json = load.json
            assertTrue(json["requestId"]!!.jsonPrimitive.int > 0)
            assertEquals("s1", json["sessionId"]!!.jsonPrimitive.content)
            assertEquals(true, json["autoplay"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(42.0, json["currentTime"]!!.jsonPrimitive.double, 0.0)
            assertEquals(listOf(1), json["activeTrackIds"]!!.jsonArray.map { it.jsonPrimitive.int })
            val media = json["media"]!!.jsonObject
            assertEquals("http://192.168.1.20:8080/p/abc/master.m3u8", media["contentId"]!!.jsonPrimitive.content)
            assertEquals("http://192.168.1.20:8080/p/abc/master.m3u8", media["contentUrl"]!!.jsonPrimitive.content)
            assertEquals("application/x-mpegURL", media["contentType"]!!.jsonPrimitive.content)
            assertEquals("BUFFERED", media["streamType"]!!.jsonPrimitive.content)
            assertEquals("ts", media["hlsSegmentFormat"]!!.jsonPrimitive.content)
            val metadata = media["metadata"]!!.jsonObject
            assertEquals(0, metadata["metadataType"]!!.jsonPrimitive.int)
            assertEquals("Episode 1", metadata["title"]!!.jsonPrimitive.content)
            val track = media["tracks"]!!.jsonArray.single().jsonObject
            assertEquals(1, track["trackId"]!!.jsonPrimitive.int)
            assertEquals("TEXT", track["type"]!!.jsonPrimitive.content)
            assertEquals("SUBTITLES", track["subtype"]!!.jsonPrimitive.content)
            assertEquals("http://192.168.1.20:8080/s/1.vtt", track["trackContentId"]!!.jsonPrimitive.content)
            assertEquals("text/vtt", track["trackContentType"]!!.jsonPrimitive.content)
            assertEquals("Türkçe", track["name"]!!.jsonPrimitive.content)
            assertEquals("tr", track["language"]!!.jsonPrimitive.content)

            assertEquals(PlayerState.PLAYING, c.media.value?.playerState)
        }

    @Test fun liveLoadOmitsCurrentTime() =
        test {
            val c = newClient()
            c.connect(device)
            c.load(hlsRequest(streamType = StreamType.LIVE))

            val json = next(MEDIA, "LOAD").json
            assertFalse(json.containsKey("currentTime"))
            val media = json["media"]!!.jsonObject
            assertEquals("LIVE", media["streamType"]!!.jsonPrimitive.content)
            assertFalse(media.containsKey("hlsSegmentFormat"))
        }

    @Test fun loadFailureSurfacesAsException() =
        test {
            dmr.loadReply = { id ->
                buildJsonObject {
                    put("type", "LOAD_FAILED")
                    put("requestId", id)
                }
            }
            val c = newClient()
            c.connect(device)
            try {
                c.load(hlsRequest())
                fail("load should have failed")
            } catch (e: CastRequestException) {
                assertEquals("LOAD_FAILED", e.type)
            }
            assertEquals(ConnectionState.Connected(device), c.connection.value)
        }

    @Test fun mediaStatusUpdatesFlow() =
        test {
            val c = newClient()
            c.connect(device)
            assertNull(c.media.value)
            dmr.mediaSessionId = 7
            dmr.playerState = "PLAYING"
            dmr.currentTime = 10.0
            receiver.send(CastMessage("t1", "*", MEDIA, dmr.mediaStatus().toString()))

            val status = c.media.first { it != null }!!
            assertEquals(MediaStatus(7, PlayerState.PLAYING, 10.0, 120.0, null, emptyList()), status)
        }

    @Test fun controlCommandsUseMediaSessionId() =
        test {
            val c = newClient()
            c.connect(device)
            pushMediaStatus(sessionId = 7, state = "PLAYING", time = 10.0)

            c.pause()
            val pause = next(MEDIA, "PAUSE")
            assertEquals("t1", pause.message.destinationId)
            assertEquals(7, pause.json["mediaSessionId"]!!.jsonPrimitive.int)
            assertEquals(PlayerState.PAUSED, c.media.value?.playerState)

            c.play()
            assertEquals(7, next(MEDIA, "PLAY").json["mediaSessionId"]!!.jsonPrimitive.int)

            c.seek(30.0)
            val seek = next(MEDIA, "SEEK")
            assertEquals(7, seek.json["mediaSessionId"]!!.jsonPrimitive.int)
            assertEquals(30.0, seek.json["currentTime"]!!.jsonPrimitive.double, 0.0)

            c.setActiveTracks(listOf(2))
            val tracks = next(MEDIA, "EDIT_TRACKS_INFO")
            assertEquals(7, tracks.json["mediaSessionId"]!!.jsonPrimitive.int)
            assertEquals(listOf(2), tracks.json["activeTrackIds"]!!.jsonArray.map { it.jsonPrimitive.int })

            c.stopMedia()
            assertEquals(7, next(MEDIA, "STOP").json["mediaSessionId"]!!.jsonPrimitive.int)
        }

    @Test fun commandWithoutMediaSessionFails() =
        test {
            val c = newClient()
            c.connect(device)
            try {
                c.pause()
                fail("pause without a media session should fail")
            } catch (_: CastException) {
            }
        }

    @Test fun volumeCommandsGoToReceiver() =
        test {
            val c = newClient()
            c.connect(device)

            c.setVolume(0.25)
            val level = next(RECEIVER, "SET_VOLUME")
            assertEquals("receiver-0", level.message.destinationId)
            assertEquals(
                0.25,
                level.json["volume"]!!
                    .jsonObject["level"]!!
                    .jsonPrimitive.double,
                0.0,
            )
            assertEquals(VolumeState(0.25, false), c.volume.value)

            c.setMuted(true)
            val mute = next(RECEIVER, "SET_VOLUME")
            assertEquals(
                true,
                mute.json["volume"]!!
                    .jsonObject["muted"]!!
                    .jsonPrimitive.content
                    .toBoolean(),
            )
            assertEquals(VolumeState(0.25, true), c.volume.value)
        }

    @Test fun answersPingWithPong() =
        test {
            val c = newClient()
            c.connect(device)
            receiver.send(CastMessage("receiver-0", "sender-0", HEARTBEAT, """{"type":"PING"}"""))

            val pong = next(HEARTBEAT, "PONG")
            assertEquals("sender-0", pong.message.sourceId)
            assertEquals("receiver-0", pong.message.destinationId)
        }

    @Test fun sendsPingPeriodically() =
        test {
            val c = newClient()
            c.connect(device)
            val ping = next(HEARTBEAT, "PING")
            assertEquals("receiver-0", ping.message.destinationId)
            next(HEARTBEAT, "PING")
        }

    @Test fun reconnectsAfterSilence() =
        test {
            dmr.appRunning = true
            val c = newClient(heartbeatTimeoutMillis = 500)
            c.connect(device)
            dmr.ignoredTypes = setOf("PING") // the receiver goes quiet

            c.connection.first { it is ConnectionState.Connecting }
            dmr.ignoredTypes = emptySet()
            c.connection.first { it == ConnectionState.Connected(device) }
            assertEquals(2, channelsCreated)
        }

    @Test fun reconnectsAfterConnectionDropAndResumesMediaStatus() =
        test {
            dmr.appRunning = true
            val c = newClient()
            c.connect(device)
            pushMediaStatus(sessionId = 3, state = "PLAYING", time = 50.0)
            dmr.currentTime = 55.0
            receiver.dropConnection()

            c.connection.first { it is ConnectionState.Connecting }
            c.connection.first { it == ConnectionState.Connected(device) }
            assertEquals(55.0, c.media.first { it?.currentTimeSeconds == 55.0 }!!.currentTimeSeconds, 0.0)
            assertEquals(2, channelsCreated)
        }

    @Test fun failsAfterThreeReconnectAttempts() =
        test {
            val c = newClient()
            c.connect(device)
            receiver.close() // gone for good

            val failed = c.connection.first { it is ConnectionState.Failed } as ConnectionState.Failed
            assertEquals(device, failed.device)
            assertEquals(4, channelsCreated) // initial + 3 attempts
            assertNull(c.media.value)
        }

    @Test fun requestTimesOutWithoutResponse() =
        test {
            val c = newClient(requestTimeoutMillis = 300)
            c.connect(device)
            pushMediaStatus(sessionId = 1, state = "PLAYING", time = 0.0)
            dmr.ignoredTypes = setOf("PAUSE")

            val start = System.nanoTime()
            try {
                c.pause()
                fail("pause should have timed out")
            } catch (_: CastTimeoutException) {
            }
            assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000)
            assertEquals(ConnectionState.Connected(device), c.connection.value)
        }

    @Test fun transportCloseClearsMediaButStaysConnected() =
        test {
            val c = newClient()
            c.connect(device)
            pushMediaStatus(sessionId = 1, state = "PLAYING", time = 0.0)

            receiver.send(CastMessage("t1", "sender-0", CONNECTION, """{"type":"CLOSE"}"""))
            c.media.first { it == null }
            assertEquals(ConnectionState.Connected(device), c.connection.value)
        }

    @Test fun receiverStatusWithoutAppClearsMediaAndNextLoadRelaunches() =
        test {
            val c = newClient()
            c.connect(device)
            next(RECEIVER, "LAUNCH")
            pushMediaStatus(sessionId = 1, state = "PLAYING", time = 0.0)

            dmr.appRunning = false // e.g. the user opened another app on the TV
            receiver.send(CastMessage("receiver-0", "*", RECEIVER, dmr.receiverStatus().toString()))
            c.media.first { it == null }
            assertEquals(ConnectionState.Connected(device), c.connection.value)

            c.load(hlsRequest())
            next(RECEIVER, "LAUNCH")
            assertEquals("t1", next(CONNECTION, "CONNECT").message.destinationId)
            next(MEDIA, "LOAD")
        }

    @Test fun disconnectClosesWithoutStoppingReceiverApp() =
        test {
            val c = newClient()
            c.connect(device)
            pushMediaStatus(sessionId = 1, state = "PLAYING", time = 0.0)
            c.disconnect()

            val seen = mutableListOf<Received>()
            while (true) {
                val r = runCatching { nextNonHeartbeat() }.getOrNull() ?: break
                seen += r
                if (r.type == "CLOSE" && r.message.destinationId == "receiver-0") break
            }
            assertTrue(seen.any { it.type == "CLOSE" && it.message.destinationId == "t1" })
            assertFalse(seen.any { it.type == "STOP" })
            assertEquals(ConnectionState.Disconnected, c.connection.value)
            assertNull(c.media.value)
        }

    @Test fun connectFailureReportsFailedState() =
        test {
            receiver.close()
            val c = newClient()
            try {
                c.connect(device)
                fail("connect should fail")
            } catch (e: CastConnectException) {
                // The raw socket error is kept as the cause, never thrown as-is.
                assertEquals(device, e.device)
                assertTrue(e.cause is java.io.IOException)
                assertFalse(e.cause is CastException)
            }
            assertTrue(c.connection.value is ConnectionState.Failed)
        }

    @Test fun sendOnDeadChannelThrowsCastExceptionNotRawIoException() =
        test {
            val c = newClient()
            c.connect(device)
            // The channel's own send() throws a plain IOException once it is closed.
            channels.last().close()
            try {
                c.setVolume(0.3)
                fail("setVolume should fail")
            } catch (e: Exception) {
                assertTrue("got ${e.javaClass}", e is CastException)
            }
        }

    /** Every channel the client created has ended (collecting a dead channel completes at once). */
    private suspend fun assertAllChannelsEnded() {
        channels.forEachIndexed { i, ch ->
            val ended = withTimeoutOrNull(2_000) { runCatching { ch.incoming.collect { } } }
            assertTrue("channel #$i of ${channels.size} is still open", ended != null)
        }
    }

    /**
     * Drops the connection, holds the reconnect's last open step (media GET_STATUS) and releases it
     * while [interrupt] runs, so the reconnect finishes opening while being superseded.
     */
    private suspend fun raceReconnectAgainst(
        jitterMicros: Long,
        interrupt: suspend (CastClient) -> Unit,
    ) {
        dmr.appRunning = true
        dmr.intercept = null
        val c = newClient()
        c.connect(device)
        val held = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        dmr.intercept = { m, json ->
            if (m.namespace == MEDIA && json["type"]!!.jsonPrimitive.content == "GET_STATUS") {
                held.complete(Unit)
                release.await(5, TimeUnit.SECONDS)
            }
            null
        }
        receiver.dropConnection()
        held.await()
        release.countDown()
        if (jitterMicros > 0) Thread.sleep(0, (jitterMicros * 1000).toInt().coerceAtMost(999_999))
        interrupt(c)
    }

    @Test fun disconnectWhileReconnectCompletesClosesEveryChannel() =
        test {
            repeat(15) { i ->
                raceReconnectAgainst(jitterMicros = (i % 5) * 200L) { it.disconnect() }
                delay(100)
                val c = client!!
                assertEquals(ConnectionState.Disconnected, c.connection.value)
                assertNull(c.media.value)
                assertNull(c.volume.value)
            }
            dmr.intercept = null
            assertAllChannelsEnded()
        }

    @Test fun connectToOtherDeviceWhileReconnectCompletesClosesOldChannels() =
        test {
            val other = FakeCastReceiver()
            val otherDmr = FakeDefaultMediaReceiver(appRunning = true)
            other.autoRespond = otherDmr::respond
            val otherDevice = CastDevice(id = "tv-2", name = "Bedroom TV", host = "127.0.0.1", port = other.start())
            try {
                repeat(10) { i ->
                    raceReconnectAgainst(jitterMicros = (i % 5) * 200L) { it.connect(otherDevice) }
                    delay(100)
                    val c = client!!
                    assertEquals(ConnectionState.Connected(otherDevice), c.connection.value)
                    assertNull(c.media.value)
                    c.disconnect()
                }
                dmr.intercept = null
                assertAllChannelsEnded()
            } finally {
                other.close()
            }
        }

    @Test fun statusArrivingDuringGracefulCloseDoesNotLeakIntoState() =
        test {
            repeat(10) {
                dmr.intercept = null
                val c = newClient()
                c.connect(device)
                pushMediaStatus(sessionId = 1, state = "PLAYING", time = 0.0)
                dmr.mediaSessionId = 9
                dmr.intercept = { m, json ->
                    if (m.namespace == CONNECTION && json["type"]!!.jsonPrimitive.content == "CLOSE" &&
                        m.destinationId == "t1"
                    ) {
                        listOf(
                            CastMessage("t1", "sender-0", MEDIA, dmr.mediaStatus().toString()),
                            CastMessage("receiver-0", "sender-0", RECEIVER, dmr.receiverStatus().toString()),
                        )
                    } else {
                        null
                    }
                }
                c.disconnect()
                delay(50)
                assertEquals(ConnectionState.Disconnected, c.connection.value)
                assertNull(c.media.value)
                assertNull(c.volume.value)
            }
        }

    @Test fun reconnectDoesNotLaunchReceiverApp() =
        test {
            val c = newClient()
            c.connect(device)
            next(MEDIA, "GET_STATUS") // end of the initial connect
            pushMediaStatus(sessionId = 1, state = "PLAYING", time = 0.0)
            dmr.appRunning = false // the TV switched to another app meanwhile
            receiver.dropConnection()

            c.connection.first { it is ConnectionState.Connecting }
            c.connection.first { it == ConnectionState.Connected(device) }
            delay(200)
            val after = generateSequence { receiver.received.tryReceive().getOrNull() }.toList()
            val types =
                after.filter { it.namespace != HEARTBEAT }.map {
                    it.destinationId + ":" +
                        Json
                            .parseToJsonElement(it.payloadUtf8)
                            .jsonObject["type"]!!
                            .jsonPrimitive.content
                }
            assertEquals(listOf("receiver-0:CONNECT", "receiver-0:GET_STATUS"), types)
            assertNull(c.media.value)
            assertFalse(dmr.appRunning)
        }

    @Test fun closeFromReplacedTransportIsIgnored() =
        test {
            dmr.appRunning = true
            val c = newClient()
            c.connect(device)
            dmr.transportId = "t2" // the app was relaunched (e.g. by another sender)
            receiver.send(CastMessage("receiver-0", "*", RECEIVER, dmr.receiverStatus().toString()))
            delay(100)
            receiver.send(CastMessage("t1", "sender-0", CONNECTION, """{"type":"CLOSE"}"""))
            delay(100)

            c.load(hlsRequest())
            val seen = mutableListOf<Received>()
            while (true) {
                val r = nextNonHeartbeat()
                seen += r
                if (r.type == "LOAD") break
            }
            assertFalse(seen.any { it.type == "LAUNCH" })
            assertEquals("t2", seen.last().message.destinationId)
            assertTrue(seen.any { it.type == "CONNECT" && it.message.destinationId == "t2" })
        }

    @Test fun connectCancelledWhileWaitingToInstallNeverLeaksTheOpenedSession() =
        test {
            dmr.appRunning = true
            val c = newClient()
            val held = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            dmr.intercept = { m, json ->
                if (m.namespace == MEDIA && json["type"]!!.jsonPrimitive.content == "GET_STATUS") {
                    held.complete(Unit)
                    release.await(5, TimeUnit.SECONDS)
                }
                null
            }
            val connecting = scope.launch { c.connect(device) }
            held.await()
            c.lifecycle.lock() // contended install: connect will suspend on the lock
            release.countDown()
            delay(200) // the session finishes opening and connect waits to install it
            connecting.cancel()
            c.lifecycle.unlock()
            connecting.join()
            dmr.intercept = null

            c.disconnect()
            assertAllChannelsEnded()
            assertEquals(ConnectionState.Disconnected, c.connection.value)
        }
}
