package io.github.yunuserdemsihhatli.homecast.playback

import io.github.yunuserdemsihhatli.homecast.capture.MediaKind
import io.github.yunuserdemsihhatli.homecast.capture.SubtitleFormat
import io.github.yunuserdemsihhatli.homecast.capture.SubtitleSource
import io.github.yunuserdemsihhatli.homecast.capture.UpstreamHeaders
import io.github.yunuserdemsihhatli.homecast.capture.VideoCandidate
import io.github.yunuserdemsihhatli.homecast.cast.CastConnectException
import io.github.yunuserdemsihhatli.homecast.cast.CastDevice
import io.github.yunuserdemsihhatli.homecast.cast.CastException
import io.github.yunuserdemsihhatli.homecast.cast.CastRequestException
import io.github.yunuserdemsihhatli.homecast.cast.ConnectionState
import io.github.yunuserdemsihhatli.homecast.cast.DeviceStore
import io.github.yunuserdemsihhatli.homecast.cast.FakeCastRemote
import io.github.yunuserdemsihhatli.homecast.cast.StreamType
import io.github.yunuserdemsihhatli.homecast.proxy.ProxyException
import io.github.yunuserdemsihhatli.homecast.proxy.ProxyServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

private const val SRT = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerTest {
    private val upstream = MockWebServer()
    private val proxy = ProxyServer(OkHttpClient())
    private val tv = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    private val cast = FakeCastRemote()
    private val wifi = FakeWifi("192.168.1.50")
    private val devices = FakeDeviceStore()
    private val tvDevice = CastDevice(id = "tv-1", name = "Living room", host = "192.168.1.20")
    private var serviceStarts = 0
    private var serviceStops = 0

    private class FakeWifi(
        initial: String?,
    ) : WifiAddressProvider {
        override val address = MutableStateFlow(initial)
    }

    private class FakeDeviceStore(
        var last: CastDevice? = null,
    ) : DeviceStore {
        override val saved: StateFlow<List<CastDevice>> = MutableStateFlow(emptyList())

        override fun save(device: CastDevice) = Unit

        override fun remove(device: CastDevice) = Unit

        override fun lastUsed(): CastDevice? = last
    }

    @Before fun setUp() {
        upstream.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.url.encodedPath) {
                        "/vod.m3u8" -> text(playlist(live = false))
                        "/vod2.m3u8" -> text(playlist(live = false))
                        "/live.m3u8" -> text(playlist(live = true))
                        "/forbidden.m3u8" -> text("nope", code = 403)
                        "/slow.m3u8" -> slow()
                        "/seg.ts" -> MockResponse.Builder().body(Buffer().write(ts())).build()
                        else -> text("missing", code = 404)
                    }
            }
        upstream.start()
    }

    @After fun tearDown() {
        proxy.stop()
        upstream.close()
    }

    private fun text(
        body: String,
        code: Int = 200,
    ) = MockResponse
        .Builder()
        .code(code)
        .body(body)
        .build()

    private fun slow() =
        MockResponse
            .Builder()
            .headersDelay(30, TimeUnit.SECONDS)
            .body(playlist(live = false))
            .build()

    private fun ts() = ByteArray(188 * 8).also { b -> for (i in 0 until 8) b[i * 188] = 0x47 }

    private fun playlist(live: Boolean) =
        buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg.ts\n")
            if (!live) append("#EXT-X-ENDLIST\n")
        }

    private fun candidate(
        path: String,
        title: String = "Episode 1",
    ) = VideoCandidate(
        url = upstream.url(path).toString(),
        kind = MediaKind.HLS,
        headers = UpstreamHeaders(referer = "https://page.example/", origin = null, userAgent = "Test", cookie = null),
        pageUrl = "https://page.example/",
        pageTitle = title,
        capturedAtMillis = 0,
    )

    private fun inlineSubtitle(label: String) =
        SubtitleSource(
            id = label,
            label = label,
            language = "tr",
            format = SubtitleFormat.VTT,
            inlineVtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nMerhaba\n",
        )

    private fun TestScope.controller(
        discover: () -> Flow<List<CastDevice>> = { emptyFlow() },
    ): Pair<PlaybackController, List<PlaybackError>> {
        val controller =
            PlaybackController(
                scope = backgroundScope,
                proxy = proxy,
                cast = cast,
                wifi = wifi,
                devices = devices,
                serviceStarter = { serviceStarts++ },
                serviceStopper = { serviceStops++ },
                proxyBindHost = "127.0.0.1",
                discover = discover,
            )
        val errors = mutableListOf<PlaybackError>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.errors.toList(errors) }
        runCurrent()
        return controller to errors
    }

    private fun connected() {
        cast.connection.value = ConnectionState.Connected(tvDevice)
    }

    /** The TV reaches the proxy at the advertised Wi-Fi address; in tests it listens on loopback. */
    private fun fetchFromTv(url: String): okhttp3.Response {
        val loopback =
            url
                .toHttpUrl()
                .newBuilder()
                .host("127.0.0.1")
                .build()
        return tv.newCall(Request.Builder().url(loopback).build()).execute()
    }

    @Test fun playFailsWithoutWifiAddress() =
        runTest {
            connected()
            wifi.address.value = null
            val (controller, errors) = controller()

            controller.playOnTv(candidate("/vod.m3u8"), emptyList())

            assertEquals(listOf(PlaybackError.NoWifi), errors)
            assertTrue(cast.loadRequests.isEmpty())
            assertNull(controller.nowPlaying.value)
            assertEquals(0, serviceStarts)
        }

    @Test fun playFailsWithoutConnectedDevice() =
        runTest {
            val (controller, errors) = controller()

            controller.playOnTv(candidate("/vod.m3u8"), emptyList())

            assertEquals(listOf(PlaybackError.NoDevice), errors)
            assertTrue(cast.calls.isEmpty())
            assertNull(controller.nowPlaying.value)
        }

    @Test fun playConnectsToLastUsedDeviceThenLoads() =
        runTest {
            devices.last = tvDevice
            val (controller, errors) = controller()

            controller.playOnTv(candidate("/vod.m3u8"), emptyList())

            assertEquals(emptyList<PlaybackError>(), errors)
            val calls = cast.calls
            assertEquals(FakeCastRemote.Call.Connect(tvDevice), calls[0])
            assertTrue(calls[1] is FakeCastRemote.Call.Load)
            assertEquals(2, calls.size)
        }

    @Test fun playStartsServiceAndLoadsProxyUrl() =
        runTest {
            connected()
            val (controller, errors) = controller()

            controller.playOnTv(candidate("/vod.m3u8", title = "Episode 1"), listOf(inlineSubtitle("Türkçe")), 0)

            assertEquals(emptyList<PlaybackError>(), errors)
            val load = cast.loadRequests.single()
            assertTrue(load.url, load.url.startsWith("http://192.168.1.50:${proxy.port}/"))
            assertEquals(StreamType.BUFFERED, load.streamType)
            assertEquals("Episode 1", load.title)
            assertEquals(0.0, load.startTimeSeconds, 0.0)
            assertEquals(1, load.subtitles.size)
            assertEquals(listOf(load.subtitles[0].trackId), load.activeTrackIds)
            assertEquals("before LOAD and after it succeeded", 2, serviceStarts)
            val nowPlaying = assertNotNullValue(controller.nowPlaying.value)
            assertEquals("Episode 1", nowPlaying.title)
            assertEquals(load.url, nowPlaying.session.mediaUrl)
            fetchFromTv(load.url).use { assertEquals(200, it.code) }
        }

    @Test fun liveCandidateLoadsAsLive() =
        runTest {
            connected()
            val (controller, _) = controller()

            controller.playOnTv(candidate("/live.m3u8"), emptyList())

            assertEquals(StreamType.LIVE, cast.loadRequests.single().streamType)
            assertTrue(assertNotNullValue(controller.nowPlaying.value).session.isLive)
        }

    @Test fun switchingEpisodeClosesPreviousSession() =
        runTest {
            connected()
            val (controller, errors) = controller()
            controller.playOnTv(candidate("/vod.m3u8", title = "Episode 1"), emptyList())
            val first = cast.loadRequests.single()
            fetchFromTv(first.url).use { assertEquals(200, it.code) }

            controller.playOnTv(candidate("/vod2.m3u8", title = "Episode 2"), emptyList())

            assertEquals(emptyList<PlaybackError>(), errors)
            assertEquals(2, cast.loadRequests.size)
            val second = cast.loadRequests[1]
            assertEquals("Episode 2", second.title)
            fetchFromTv(first.url).use { assertEquals(404, it.code) }
            fetchFromTv(second.url).use { assertEquals(200, it.code) }
            assertEquals("Episode 2", assertNotNullValue(controller.nowPlaying.value).title)
            assertEquals("twice per play", 4, serviceStarts)
        }

    @Test fun addSubtitleFileReloadsAtCurrentPosition() =
        runTest {
            connected()
            val (controller, errors) = controller()
            controller.playOnTv(candidate("/vod.m3u8"), listOf(inlineSubtitle("Embedded")))
            cast.media.value = cast.media.value!!.copy(currentTimeSeconds = 120.5)
            val srt = "1\r\n00:00:01,000 --> 00:00:02,500\r\nŞimdi\r\n".toByteArray(Charsets.UTF_8)

            controller.addSubtitleFile(srt, "My file")

            assertEquals(emptyList<PlaybackError>(), errors)
            assertEquals(2, cast.loadRequests.size)
            val reload = cast.loadRequests[1]
            assertEquals(120.5, reload.startTimeSeconds, 0.0)
            assertEquals(2, reload.subtitles.size)
            val added = reload.subtitles.last()
            assertEquals("My file", added.label)
            assertEquals(listOf(added.trackId), reload.activeTrackIds)
            assertEquals(cast.loadRequests[0].url, reload.url)
            fetchFromTv(added.url).use {
                val body = it.body.string()
                assertTrue(body, body.startsWith("WEBVTT"))
                assertTrue(body, "00:00:01.000 --> 00:00:02.500" in body && "Şimdi" in body)
            }
            assertEquals(reload.subtitles, assertNotNullValue(controller.nowPlaying.value).session.subtitles)
        }

    @Test fun addSubtitleFileOnLiveStreamReloadsWithoutPosition() =
        runTest {
            connected()
            val (controller, _) = controller()
            controller.playOnTv(candidate("/live.m3u8"), emptyList())
            cast.media.value = cast.media.value!!.copy(currentTimeSeconds = 300.0)

            controller.addSubtitleFile(SRT.toByteArray(), "Live subs")

            val reload = cast.loadRequests[1]
            assertEquals(StreamType.LIVE, reload.streamType)
            assertEquals(0.0, reload.startTimeSeconds, 0.0)
            assertEquals(listOf(reload.subtitles.single().trackId), reload.activeTrackIds)
        }

    @Test fun selectSubtitleUsesEditTracks() =
        runTest {
            connected()
            val (controller, errors) = controller()
            controller.playOnTv(candidate("/vod.m3u8"), listOf(inlineSubtitle("A"), inlineSubtitle("B")))
            val trackB =
                cast.loadRequests
                    .single()
                    .subtitles[1]
                    .trackId

            controller.selectSubtitle(trackB)
            controller.selectSubtitle(null)

            assertEquals(emptyList<PlaybackError>(), errors)
            val edits = cast.calls.filterIsInstance<FakeCastRemote.Call.SetActiveTracks>()
            assertEquals(
                listOf(
                    FakeCastRemote.Call.SetActiveTracks(listOf(trackB)),
                    FakeCastRemote.Call.SetActiveTracks(emptyList()),
                ),
                edits,
            )
            assertEquals(1, cast.loadRequests.size)
        }

    @Test fun proxyErrorsSurfaceAsPlaybackErrors() =
        runTest {
            connected()
            val (controller, errors) = controller()

            controller.playOnTv(candidate("/forbidden.m3u8"), emptyList())

            assertEquals(listOf(PlaybackError.Proxy(ProxyException.Reason.UPSTREAM_REJECTED)), errors)
            assertTrue(cast.loadRequests.isEmpty())
            assertNull(controller.nowPlaying.value)
            assertEquals("started before LOAD only", 1, serviceStarts)
            assertFalse(controller.starting.value)
        }

    @Test fun castErrorsSurfaceAsPlaybackErrors() =
        runTest {
            connected()
            cast.loadFailure = CastRequestException("LOAD_FAILED", null)
            val (controller, errors) = controller()

            controller.playOnTv(candidate("/vod.m3u8"), emptyList())

            assertEquals(listOf(PlaybackError.Cast("LOAD_FAILED")), errors)
            assertNull(controller.nowPlaying.value)
            assertEquals("started before LOAD only", 1, serviceStarts)
            assertFalse(controller.starting.value)
        }

    @Test fun unreachableTvSurfacesAsCannotReachTv() =
        runTest {
            devices.last = tvDevice
            cast.connectFailure = CastConnectException(tvDevice, java.net.ConnectException("failed to connect"))
            val (controller, errors) = controller()

            controller.playOnTv(candidate("/vod.m3u8"), emptyList())

            assertEquals(listOf(PlaybackError.CannotReachTv("Living room")), errors)
            assertTrue(cast.loadRequests.isEmpty())
            assertNull(controller.nowPlaying.value)
        }

    @Test fun playFollowsLastUsedTvToItsNewAddress() =
        runTest {
            devices.last = tvDevice
            val moved = tvDevice.copy(host = "192.168.1.77")
            cast.connectFailureFor = {
                if (it.host == tvDevice.host) CastConnectException(it, java.net.ConnectException("refused")) else null
            }
            val (controller, errors) = controller(discover = { flowOf(listOf(moved)) })

            controller.playOnTv(candidate("/vod.m3u8"), emptyList())

            assertEquals(emptyList<PlaybackError>(), errors)
            assertEquals(
                listOf(FakeCastRemote.Call.Connect(tvDevice), FakeCastRemote.Call.Connect(moved)),
                cast.calls.filterIsInstance<FakeCastRemote.Call.Connect>(),
            )
            assertEquals(1, cast.loadRequests.size)
        }

    @Test fun serviceStartsBeforeLoadAndStartingIsSetWhileInFlight() =
        runTest {
            connected()
            cast.loadGate = CompletableDeferred()
            val (controller, _) = controller()
            val play = launch { controller.playOnTv(candidate("/vod.m3u8"), emptyList()) }
            withContext(Dispatchers.Default) { while (cast.loadRequests.isEmpty()) delay(10) }

            assertEquals(1, serviceStarts)
            assertTrue(controller.starting.value)

            cast.loadGate!!.complete(Unit)
            play.join()
            assertFalse(controller.starting.value)
            assertEquals(2, serviceStarts)
        }

    @Test fun playAgainReloadsSameSessionAtLastPositionWithSameSubtitle() =
        runTest {
            connected()
            val (controller, errors) = controller()
            controller.playOnTv(candidate("/vod.m3u8"), listOf(inlineSubtitle("Türkçe")), 0)
            val first = cast.loadRequests.single()
            cast.media.value = cast.media.value!!.copy(currentTimeSeconds = 754.0)
            runCurrent()
            // The receiver app was closed on the TV: the media is gone, the connection stays.
            cast.media.value = null
            wifi.address.value = "192.168.1.51"
            runCurrent()

            controller.playAgain()

            assertEquals(emptyList<PlaybackError>(), errors)
            val again = cast.loadRequests[1]
            assertEquals(754.0, again.startTimeSeconds, 0.0)
            assertEquals(first.activeTrackIds, again.activeTrackIds)
            assertTrue(again.url, again.url.startsWith("http://192.168.1.51:"))
            assertEquals(first.url.toHttpUrl().encodedPath, again.url.toHttpUrl().encodedPath)
            fetchFromTv(again.url).use { assertEquals(200, it.code) }
            assertEquals(again.url, assertNotNullValue(controller.nowPlaying.value).session.mediaUrl)
        }

    @Test fun playAgainAfterTheVideoFinishedStartsFromTheBeginning() =
        runTest {
            connected()
            val (controller, _) = controller()
            controller.playOnTv(candidate("/vod.m3u8"), emptyList())
            cast.media.value =
                cast.media.value!!.copy(
                    playerState = io.github.yunuserdemsihhatli.homecast.cast.PlayerState.IDLE,
                    idleReason = "FINISHED",
                    currentTimeSeconds = 3_600.0,
                )
            runCurrent()

            controller.playAgain()

            assertEquals(0.0, cast.loadRequests[1].startTimeSeconds, 0.0)
        }

    @Test fun playAgainReconnectsToTheLastUsedTv() =
        runTest {
            connected()
            devices.last = tvDevice
            val (controller, errors) = controller()
            controller.playOnTv(candidate("/live.m3u8"), emptyList())
            cast.connection.value = ConnectionState.Disconnected
            cast.media.value = null

            controller.playAgain()

            assertEquals(emptyList<PlaybackError>(), errors)
            assertEquals(
                FakeCastRemote.Call.Connect(tvDevice),
                cast.calls.filterIsInstance<FakeCastRemote.Call.Connect>().last(),
            )
            assertEquals(StreamType.LIVE, cast.loadRequests[1].streamType)
        }

    @Test fun playAgainWithNothingPlayingDoesNothing() =
        runTest {
            connected()
            val (controller, errors) = controller()

            controller.playAgain()

            assertTrue(cast.calls.isEmpty())
            assertEquals(emptyList<PlaybackError>(), errors)
            assertEquals(0, serviceStarts)
        }

    @Test fun rebindsProxyWhenAddressChanges() =
        runTest {
            connected()
            val (controller, _) = controller()
            controller.playOnTv(candidate("/vod.m3u8"), emptyList())
            assertTrue(cast.loadRequests[0].url.startsWith("http://192.168.1.50:"))

            wifi.address.value = "192.168.1.51"
            runCurrent()

            // The advertised host follows the network even before the next LOAD.
            val token = assertNotNullValue(controller.nowPlaying.value).session.token
            val probe = proxy.addSubtitle(token, inlineSubtitle("probe"))
            assertTrue(probe.url, probe.url.startsWith("http://192.168.1.51:"))

            controller.playOnTv(candidate("/vod2.m3u8"), emptyList())
            assertTrue(cast.loadRequests[1].url, cast.loadRequests[1].url.startsWith("http://192.168.1.51:"))
        }

    @Test fun lostWifiKeepsPlayingButNextPlayFails() =
        runTest {
            connected()
            val (controller, errors) = controller()
            controller.playOnTv(candidate("/vod.m3u8"), emptyList())

            wifi.address.value = null
            runCurrent()

            assertNotNull(controller.nowPlaying.value)
            controller.playOnTv(candidate("/vod2.m3u8"), emptyList())
            assertEquals(listOf(PlaybackError.NoWifi), errors)
            assertEquals(1, cast.loadRequests.size)
        }

    @Test fun stopClosesSessionAndStopsService() =
        runTest {
            connected()
            val (controller, errors) = controller()
            controller.playOnTv(candidate("/vod.m3u8"), emptyList())
            val url = cast.loadRequests.single().url

            controller.stop()

            assertEquals(emptyList<PlaybackError>(), errors)
            assertTrue(FakeCastRemote.Call.StopMedia in cast.calls)
            assertNull(controller.nowPlaying.value)
            assertEquals(1, serviceStops)
            fetchFromTv(url).use { assertEquals(404, it.code) }
        }

    @Test fun playWaitsBrieflyForWifiAddress() =
        runTest {
            connected()
            wifi.address.value = null
            val (controller, errors) = controller()

            val play = launch { controller.playOnTv(candidate("/vod.m3u8"), emptyList()) }
            delay(100)
            wifi.address.value = "192.168.1.60"
            play.join()

            assertEquals(emptyList<PlaybackError>(), errors)
            assertTrue(
                cast.loadRequests
                    .single()
                    .url
                    .startsWith("http://192.168.1.60:"),
            )
        }

    @Test fun stopCancelsPlayStuckOnSlowUpstream() =
        runTest {
            connected()
            val (controller, errors) = controller()
            val play = launch { controller.playOnTv(candidate("/slow.m3u8"), emptyList()) }
            withContext(Dispatchers.IO) { assertNotNull(upstream.takeRequest(5, TimeUnit.SECONDS)) }

            val started = System.nanoTime()
            controller.stop()
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000

            assertTrue("stop took $elapsedMillis ms", elapsedMillis < 2_000)
            play.join()
            assertEquals(emptyList<PlaybackError>(), errors)
            assertTrue(cast.loadRequests.isEmpty())
            assertNull(controller.nowPlaying.value)
            assertEquals(1, serviceStarts)
            assertEquals(1, serviceStops)
        }

    @Test fun stopCancelsPlayStuckInLoadAndClosesItsSession() =
        runTest {
            connected()
            cast.loadGate = CompletableDeferred()
            val (controller, errors) = controller()
            val play = launch { controller.playOnTv(candidate("/vod.m3u8"), emptyList()) }
            withContext(Dispatchers.Default) { while (cast.loadRequests.isEmpty()) delay(10) }
            val url = cast.loadRequests.single().url

            controller.stop()

            play.join()
            assertEquals(emptyList<PlaybackError>(), errors)
            assertNull(controller.nowPlaying.value)
            assertEquals(1, serviceStarts)
            fetchFromTv(url).use { assertEquals(404, it.code) }
        }

    @Test fun playDoesNotSwitchDeviceWhileStillConnecting() =
        runTest {
            cast.connection.value = ConnectionState.Connecting(tvDevice)
            devices.last = CastDevice(id = "tv-2", name = "Bedroom", host = "192.168.1.21")
            val (controller, errors) = controller()

            controller.playOnTv(candidate("/vod.m3u8"), emptyList())

            assertEquals(listOf(PlaybackError.Cast("Still connecting to Living room")), errors)
            assertTrue(cast.calls.isEmpty())
        }

    @Test fun invalidSubtitleFileIsReported() =
        runTest {
            connected()
            val (controller, errors) = controller()
            controller.playOnTv(candidate("/vod.m3u8"), emptyList())

            controller.addSubtitleFile(byteArrayOf(0x00, 0x7F, 0xFF.toByte(), 0x10, 0x42), "Garbage")

            assertEquals(listOf(PlaybackError.InvalidSubtitle), errors)
            assertEquals(1, cast.loadRequests.size)
            assertTrue(assertNotNullValue(controller.nowPlaying.value).session.subtitles.isEmpty())
        }

    @Test fun addSubtitleFileWithoutMediaRegistersTrackWithoutReload() =
        runTest {
            connected()
            val (controller, errors) = controller()
            controller.playOnTv(candidate("/vod.m3u8"), emptyList())
            cast.media.value = null // stopped on the TV

            controller.addSubtitleFile(SRT.toByteArray(), "Later")

            assertEquals(emptyList<PlaybackError>(), errors)
            assertEquals(1, cast.loadRequests.size)
            val track = assertNotNullValue(controller.nowPlaying.value).session.subtitles.single()
            assertEquals("Later", track.label)
            fetchFromTv(track.url).use { assertEquals(200, it.code) }
        }

    @Test fun reloadAfterAddressChangeUsesNewHost() =
        runTest {
            connected()
            val (controller, _) = controller()
            controller.playOnTv(candidate("/vod.m3u8"), listOf(inlineSubtitle("Embedded")))
            wifi.address.value = "192.168.1.51"
            runCurrent()

            controller.addSubtitleFile(SRT.toByteArray(), "New")

            val reload = cast.loadRequests[1]
            assertTrue(reload.url, reload.url.startsWith("http://192.168.1.51:"))
            assertTrue(reload.subtitles.all { it.url.startsWith("http://192.168.1.51:") })
            fetchFromTv(reload.url).use { assertEquals(200, it.code) }
            reload.subtitles.forEach { sub -> fetchFromTv(sub.url).use { assertEquals(200, it.code) } }
        }

    @Test fun stopStillCleansUpWhenStopMediaFails() =
        runTest {
            connected()
            val (controller, errors) = controller()
            controller.playOnTv(candidate("/vod.m3u8"), emptyList())
            val url = cast.loadRequests.single().url
            cast.commandFailure = CastException("connection lost")

            controller.stop()

            assertEquals(listOf(PlaybackError.Cast("connection lost")), errors)
            assertNull(controller.nowPlaying.value)
            assertEquals(1, serviceStops)
            fetchFromTv(url).use { assertEquals(404, it.code) }
        }

    private fun <T : Any> assertNotNullValue(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
