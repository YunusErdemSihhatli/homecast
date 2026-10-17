package io.github.yunuserdemsihhatli.homecast.remote

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.yunuserdemsihhatli.homecast.capture.MediaKind
import io.github.yunuserdemsihhatli.homecast.capture.SubtitleFormat
import io.github.yunuserdemsihhatli.homecast.capture.SubtitleSource
import io.github.yunuserdemsihhatli.homecast.capture.UpstreamHeaders
import io.github.yunuserdemsihhatli.homecast.capture.VideoCandidate
import io.github.yunuserdemsihhatli.homecast.cast.CastConnectException
import io.github.yunuserdemsihhatli.homecast.cast.CastDevice
import io.github.yunuserdemsihhatli.homecast.cast.CastException
import io.github.yunuserdemsihhatli.homecast.cast.ConnectionState
import io.github.yunuserdemsihhatli.homecast.cast.DeviceStore
import io.github.yunuserdemsihhatli.homecast.cast.FakeCastRemote
import io.github.yunuserdemsihhatli.homecast.cast.FakeCastRemote.Call
import io.github.yunuserdemsihhatli.homecast.cast.MediaStatus
import io.github.yunuserdemsihhatli.homecast.cast.PlayerState
import io.github.yunuserdemsihhatli.homecast.cast.VolumeState
import io.github.yunuserdemsihhatli.homecast.playback.PlaybackController
import io.github.yunuserdemsihhatli.homecast.playback.PlaybackError
import io.github.yunuserdemsihhatli.homecast.playback.WifiAddressProvider
import io.github.yunuserdemsihhatli.homecast.proxy.ProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelTest {
    private val upstream = MockWebServer()
    private val proxy = ProxyServer(OkHttpClient())
    private val cast = FakeCastRemote()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val tv = CastDevice(id = "tv", name = "Living room", host = "192.168.1.20")
    private var now = 0L
    private val ticker = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    private val discovered = MutableStateFlow<List<CastDevice>>(emptyList())

    private val wifi =
        object : WifiAddressProvider {
            override val address = MutableStateFlow<String?>("127.0.0.1")
        }

    private val store = InMemoryDeviceStore()

    private val playback =
        PlaybackController(
            scope = scope,
            proxy = proxy,
            cast = cast,
            wifi = wifi,
            devices = store,
            serviceStarter = {},
            serviceStopper = {},
            proxyBindHost = "127.0.0.1",
        )

    @Before fun setUp() {
        // Real time (not a test scheduler): playOnTv does real network I/O with real timeouts.
        Dispatchers.setMain(Dispatchers.Unconfined)
        upstream.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val endList = if (request.url.encodedPath.contains("live")) "" else "#EXT-X-ENDLIST\n"
                    return MockResponse
                        .Builder()
                        .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .body("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg.ts\n$endList")
                        .build()
                }
            }
        upstream.start()
    }

    @After fun tearDown() {
        // Cancel first: cancelling the collectors still touches Main (the view models' sharing), so it must be set.
        scope.cancel()
        Dispatchers.resetMain()
        proxy.stop()
        upstream.close()
    }

    private fun viewModel(appScope: CoroutineScope = scope): RemoteViewModel =
        RemoteViewModel(cast, playback, store, discovered, appScope, clock = { now }, ticker = ticker).also { vm ->
            // The state is shared while subscribed, as by the screen.
            scope.launch(start = CoroutineStart.UNDISPATCHED) { vm.state.collect {} }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { vm.devices.collect {} }
        }

    private fun connected() {
        cast.connection.value = ConnectionState.Connected(tv)
        cast.volume.value = VolumeState(0.4, muted = false)
    }

    private fun status(
        state: PlayerState,
        position: Double,
        duration: Double? = 100.0,
        tracks: List<Int> = emptyList(),
    ) = MediaStatus(
        mediaSessionId = 7,
        playerState = state,
        currentTimeSeconds = position,
        durationSeconds = duration,
        idleReason = null,
        activeTrackIds = tracks,
    )

    @Test fun connectByIpValidatesAndSaves() {
        val vm = viewModel()

        assertTrue(vm.connectByIp("192.168.1.31"))
        assertEquals(
            CastDevice(id = null, name = "TV (192.168.1.31)", host = "192.168.1.31", port = 8009),
            store.lastUsed(),
        )
        assertEquals(Call.Connect(store.lastUsed()!!), cast.calls.last())
        assertEquals(ConnectionState.Connected(store.lastUsed()!!), cast.connection.value)

        assertTrue(vm.connectByIp(" 192.168.1.31:8010 "))
        assertEquals(8010, store.lastUsed()!!.port)
        assertEquals("TV (192.168.1.31)", store.lastUsed()!!.name)

        val before = cast.calls.size
        for (bad in listOf(
            "tv.local",
            "999.1.1.1",
            "192.168.1",
            "192.168.1.31:0",
            "192.168.1.31:70000",
            "1.2.3.4:",
            "",
        )) {
            assertFalse(bad, vm.connectByIp(bad))
        }
        assertEquals(before, cast.calls.size)
        assertEquals(2, store.saved.value.size)
    }

    /** A view model owned by a store, as on a nav back stack entry, so the test can clear it like leaving the screen. */
    private fun ownedViewModel(
        owner: ViewModelStore,
        appScope: CoroutineScope,
    ): RemoteViewModel =
        ViewModelProvider.create(owner, viewModelFactory { initializer { viewModel(appScope) } })[
            RemoteViewModel::class,
        ]

    @Test fun connectAndStopCompleteAfterTheScreenIsLeft() {
        connected()
        play(upstream.url("/vod.m3u8").toString())
        cast.connection.value = ConnectionState.Disconnected
        // Nothing runs until the scheduler advances, so actions are still pending when the screen goes away.
        val app = StandardTestDispatcher()
        Dispatchers.setMain(app)
        val owner = ViewModelStore()
        val vm = ownedViewModel(owner, CoroutineScope(SupervisorJob() + app))

        vm.connect(tv)
        vm.stop()
        owner.clear() // the user left the remote right after tapping
        app.scheduler.advanceUntilIdle()

        assertEquals(ConnectionState.Connected(tv), cast.connection.value)
        runBlocking { withTimeout(5_000) { while (playback.nowPlaying.value != null) delay(10) } }
        assertTrue(cast.calls.contains(Call.StopMedia))
    }

    @Test fun noDeviceIsKeptUntilTheScreenCollectsMessages() =
        runBlocking {
            // Nothing connected and no saved device; the remote's view model exists but nobody collects yet.
            val vm = viewModel()

            play(upstream.url("/vod.m3u8").toString(), expectPlaying = false)

            assertEquals(
                RemoteMessage.Playback(PlaybackError.NoDevice),
                withTimeout(5_000) { vm.messages.first() },
            )
        }

    @Test fun activeSubtitleIdOnlyNamesKnownTracks() {
        connected()
        cast.media.value = status(PlayerState.PLAYING, 10.0, tracks = listOf(3))
        val vm = viewModel()

        assertNull(vm.state.value.activeSubtitleId)
    }

    @Test fun connectSavesDeviceAndFailureNeverThrows() {
        val vm = viewModel()
        cast.connectFailure = CastException("Connection refused")

        vm.connect(tv)

        assertEquals(tv, store.lastUsed())
        assertEquals(ConnectionState.Failed(tv, "Connection refused"), vm.state.value.connection)
    }

    @Test fun reconnectFollowsTheTvToItsNewAddressAndSavesIt() {
        val moved = tv.copy(host = "192.168.1.77")
        store.save(tv)
        cast.connection.value = ConnectionState.Failed(tv, "refused")
        cast.connectFailureFor = {
            if (it.host == tv.host) CastConnectException(it, java.net.ConnectException("refused")) else null
        }
        discovered.value = listOf(moved)
        val vm = viewModel()

        assertTrue(vm.reconnect())

        runBlocking { withTimeout(5_000) { cast.connection.first { it == ConnectionState.Connected(moved) } } }
        assertEquals(listOf(moved), store.saved.value)
    }

    @Test fun playAgainIsOfferedOnlyWhenTheTvLostWhatWasPlaying() {
        connected()
        val vm = viewModel()
        assertFalse("nothing playing", vm.state.value.canPlayAgain)

        play(upstream.url("/vod.m3u8").toString())
        assertFalse("playing", vm.state.value.canPlayAgain)

        cast.media.value = null
        assertTrue("dropped by the TV", vm.state.value.canPlayAgain)

        cast.connection.value = ConnectionState.Connecting(tv)
        assertFalse("while connecting", vm.state.value.canPlayAgain)

        cast.connection.value = ConnectionState.Connected(tv)
        cast.media.value = status(PlayerState.IDLE, 100.0).copy(idleReason = "FINISHED")
        assertTrue("ended", vm.state.value.canPlayAgain)

        vm.playAgain()
        runBlocking { withTimeout(5_000) { while (cast.loadRequests.size < 2) delay(10) } }
    }

    @Test fun devicesMergeDiscoveredAndSaved() {
        val saved = CastDevice(id = null, name = "TV (192.168.1.31)", host = "192.168.1.31")
        val movedTv = tv.copy(host = "192.168.1.99")
        store.save(saved)
        store.save(movedTv)
        val vm = viewModel()

        discovered.value = listOf(tv)

        assertEquals(listOf(tv, saved), vm.devices.value)
    }

    @Test fun togglePlayPausesWhenPlaying() {
        connected()
        cast.media.value = status(PlayerState.PLAYING, 10.0)
        val vm = viewModel()

        vm.togglePlay()
        assertEquals(Call.Pause, cast.calls.last())
        assertEquals(PlayerState.PAUSED, vm.state.value.playerState)

        vm.togglePlay()
        assertEquals(Call.Play, cast.calls.last())
        assertEquals(PlayerState.PLAYING, vm.state.value.playerState)
    }

    @Test fun skipClampsToZeroAndDuration() {
        connected()
        cast.media.value = status(PlayerState.PAUSED, 5.0, duration = 100.0)
        val vm = viewModel()

        vm.skip(-10)
        assertEquals(Call.Seek(0.0), cast.calls.last())

        cast.media.value = status(PlayerState.PAUSED, 95.0, duration = 100.0)
        vm.skip(10)
        assertEquals(Call.Seek(100.0), cast.calls.last())

        vm.seekTo(250.0)
        assertEquals(Call.Seek(100.0), cast.calls.last())
        vm.seekTo(-3.0)
        assertEquals(Call.Seek(0.0), cast.calls.last())
    }

    @Test fun positionAdvancesLocallyWhilePlaying() {
        connected()
        now = 1_000
        cast.media.value = status(PlayerState.PLAYING, 20.0, duration = 30.0)
        val vm = viewModel()
        assertEquals(20.0, vm.state.value.positionSeconds, 0.001)

        now = 3_500
        ticker.tryEmit(Unit)
        assertEquals(22.5, vm.state.value.positionSeconds, 0.001)

        now = 60_000 // never past the end
        ticker.tryEmit(Unit)
        assertEquals(30.0, vm.state.value.positionSeconds, 0.001)

        now = 70_000
        cast.media.value = status(PlayerState.PAUSED, 25.0, duration = 30.0)
        now = 90_000
        ticker.tryEmit(Unit)
        assertEquals(25.0, vm.state.value.positionSeconds, 0.001)
    }

    @Test fun skipStartsFromTheInterpolatedPosition() {
        connected()
        now = 0
        cast.media.value = status(PlayerState.PLAYING, 20.0, duration = 100.0)
        val vm = viewModel()

        now = 4_000
        vm.skip(10)

        assertEquals(Call.Seek(34.0), cast.calls.last())
    }

    @Test fun commandFailureBecomesAMessageNotACrash() =
        runBlocking {
            connected()
            cast.media.value = status(PlayerState.PLAYING, 10.0)
            val vm = viewModel()
            val messages = mutableListOf<RemoteMessage>()
            val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) { vm.messages.toList(messages) }
            cast.commandFailure = CastException("Timed out")

            vm.togglePlay()
            vm.setVolume(0.8)

            withTimeout(5_000) { while (messages.size < 2) delay(10) }
            collector.cancel()
            assertEquals(listOf(RemoteMessage.CommandFailed, RemoteMessage.CommandFailed), messages)
        }

    @Test fun volumeAndMute() {
        connected()
        val vm = viewModel()

        vm.setVolume(1.7)
        assertEquals(Call.SetVolume(1.0), cast.calls.last())
        vm.toggleMute()
        assertEquals(Call.SetMuted(true), cast.calls.last())
        assertTrue(vm.state.value.muted)
        assertEquals(1.0, vm.state.value.volume!!, 0.0)
    }

    @Test fun liveStateHidesSeekAndSkipIsNoOp() {
        connected()
        val vm = viewModel()
        play(upstream.url("/live.m3u8").toString())

        assertTrue(vm.state.value.isLive)
        val before = cast.calls.size
        vm.skip(10)
        vm.skip(-10)
        vm.seekTo(42.0)
        assertEquals(before, cast.calls.size)
    }

    @Test fun selectSubtitleOffSendsEmptyTracks() =
        runBlocking {
            connected()
            cast.media.value = status(PlayerState.PLAYING, 10.0, tracks = listOf(3))
            val vm = viewModel()

            vm.selectSubtitle(null)

            withTimeout(5_000) { while (cast.calls.none { it is Call.SetActiveTracks }) delay(10) }
            assertEquals(Call.SetActiveTracks(emptyList()), cast.calls.last())
            assertNull(vm.state.value.activeSubtitleId)
        }

    @Test fun stateCombinesConnectionMediaAndNowPlaying() {
        connected()
        val vm = viewModel()
        assertNull(vm.state.value.title)
        assertNull(vm.state.value.playerState)

        play(upstream.url("/vod.m3u8").toString(), subtitles = listOf(subtitle("en"), subtitle("tr")))
        val tracks =
            playback.nowPlaying.value!!
                .session.subtitles
        cast.media.value = status(PlayerState.BUFFERING, 12.0, duration = 3_600.0, tracks = listOf(tracks[1].trackId))

        val state = vm.state.value
        assertEquals(ConnectionState.Connected(tv), state.connection)
        assertEquals("Episode", state.title)
        assertEquals(PlayerState.BUFFERING, state.playerState)
        assertEquals(12.0, state.positionSeconds, 0.0)
        assertEquals(3_600.0, state.durationSeconds!!, 0.0)
        assertFalse(state.isLive)
        assertEquals(0.4, state.volume!!, 0.0)
        assertFalse(state.muted)
        assertEquals(tracks, state.subtitleOptions)
        assertEquals(listOf("en", "tr"), state.subtitleOptions.map { it.label })
        assertEquals(tracks[1].trackId, state.activeSubtitleId)

        vm.stop()
        runBlocking { withTimeout(5_000) { while (playback.nowPlaying.value != null) delay(10) } }
        assertNull(vm.state.value.title)
        assertEquals(emptyList<Any>(), vm.state.value.subtitleOptions)
    }

    private fun play(
        url: String,
        subtitles: List<SubtitleSource> = emptyList(),
        expectPlaying: Boolean = true,
    ) = runBlocking {
        val candidate =
            VideoCandidate(
                url = url,
                kind = MediaKind.HLS,
                headers =
                    UpstreamHeaders(
                        referer = "https://page.example/",
                        origin = null,
                        userAgent = "UA",
                        cookie = null,
                    ),
                pageUrl = "https://page.example/",
                pageTitle = "Episode",
                capturedAtMillis = 0,
            )
        playback.playOnTv(candidate, subtitles)
        assertEquals("play result", expectPlaying, playback.nowPlaying.value != null)
    }

    private fun subtitle(lang: String) =
        SubtitleSource(
            id = lang,
            label = lang,
            language = lang,
            format = SubtitleFormat.VTT,
            inlineVtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n",
        )

    private class InMemoryDeviceStore : DeviceStore {
        private val state = MutableStateFlow<List<CastDevice>>(emptyList())
        override val saved: StateFlow<List<CastDevice>> = state

        override fun save(device: CastDevice) {
            state.value =
                listOf(device) +
                state.value.filterNot { it.id == device.id && it.host == device.host && it.port == device.port }
        }

        override fun remove(device: CastDevice) {
            state.value = state.value.filterNot { it == device }
        }

        override fun lastUsed(): CastDevice? = state.value.firstOrNull()
    }
}
