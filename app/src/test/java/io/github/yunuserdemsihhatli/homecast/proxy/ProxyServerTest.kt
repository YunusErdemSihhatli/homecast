package io.github.yunuserdemsihhatli.homecast.proxy

import io.github.yunuserdemsihhatli.homecast.capture.MediaKind
import io.github.yunuserdemsihhatli.homecast.capture.SubtitleFormat
import io.github.yunuserdemsihhatli.homecast.capture.SubtitleSource
import io.github.yunuserdemsihhatli.homecast.capture.UpstreamHeaders
import io.github.yunuserdemsihhatli.homecast.capture.VideoCandidate
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProxyServerTest {
    private val upstream = MockWebServer()
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val abortedUpstreamCalls = ConcurrentHashMap.newKeySet<Call>()
    private val upstreamClient =
        OkHttpClient
            .Builder()
            .eventListener(
                object : EventListener() {
                    override fun canceled(call: Call) {
                        abortedUpstreamCalls.add(call)
                    }

                    override fun callFailed(
                        call: Call,
                        ioe: IOException,
                    ) {
                        abortedUpstreamCalls.add(call)
                    }
                },
            ).build()
    private val tv = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    private val proxy = ProxyServer(upstreamClient)
    private val headers =
        UpstreamHeaders(
            referer = "https://page.example/",
            origin = "https://page.example",
            userAgent = "HomeCastTest/1.0",
            cookie = "sid=42",
        )

    @Before fun setUp() {
        upstream.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                    return routes[path]?.invoke(request) ?: MockResponse.Builder().code(404).build()
                }
            }
        upstream.start()
        proxy.start("127.0.0.1")
        proxy.setAdvertisedHost("127.0.0.1")
    }

    @After fun tearDown() {
        proxy.stop()
        upstream.close()
    }

    private fun route(
        path: String,
        handler: (RecordedRequest) -> MockResponse,
    ) {
        routes[path] = handler
    }

    private fun text(
        body: String,
        code: Int = 200,
    ) = MockResponse
        .Builder()
        .code(code)
        .body(body)
        .build()

    private fun bytes(body: ByteArray) = MockResponse.Builder().body(Buffer().write(body)).build()

    private fun candidate(
        path: String,
        kind: MediaKind = MediaKind.HLS,
        url: String = upstream.url(path).toString(),
    ) = VideoCandidate(
        url = url,
        kind = kind,
        headers = headers,
        pageUrl = "https://page.example/",
        pageTitle = "Test",
        capturedAtMillis = 0,
    )

    private fun get(
        url: String,
        build: Request.Builder.() -> Unit = {},
    ): Response =
        tv
            .newCall(
                Request
                    .Builder()
                    .url(url)
                    .apply(build)
                    .build(),
            ).execute()

    private fun ts(packets: Int = 8) =
        ByteArray(188 * packets).also { b -> for (i in 0 until packets) b[i * 188] = 0x47 }

    private fun mediaPlaylist(
        segment: String = "seg.ts",
        live: Boolean = false,
    ) = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n")
        append("#EXTINF:6.0,\n$segment\n#EXTINF:6.0,\nseg2.ts?sig=abc\n")
        if (!live) append("#EXT-X-ENDLIST\n")
    }

    private fun proxiedUris(playlist: String) = playlist.lines().filter { it.isNotBlank() && !it.startsWith("#") }

    private fun upstreamOf(proxyUrl: String): String =
        ProxyUrls.parse(proxyUrl.substringAfter(":${proxy.port}"))!!.upstream

    private fun createHls(path: String) = runBlocking { proxy.createSession(candidate(path), emptyList()) }

    @Test fun rejectsWithoutRefererUpstreamButProxyAddsIt() {
        route("/v/index.m3u8") { req ->
            if (req.headers["Referer"] == "https://page.example/") text(mediaPlaylist()) else text("", 403)
        }
        assertEquals(
            403,
            OkHttpClient().newCall(Request.Builder().url(upstream.url("/v/index.m3u8")).build()).execute().code,
        )

        val session = createHls("/v/index.m3u8")
        get(session.mediaUrl).use { assertEquals(200, it.code) }
        val recorded = generateSequence { upstream.takeRequest(0, TimeUnit.SECONDS) }.last()
        assertEquals("HomeCastTest/1.0", recorded.headers["User-Agent"])
        assertEquals("https://page.example", recorded.headers["Origin"])
        assertEquals("sid=42", recorded.headers["Cookie"])
    }

    @Test fun rewritesPlaylistToProxyUrls() {
        route("/v/index.m3u8") { text(mediaPlaylist()) }
        val session = createHls("/v/index.m3u8")
        assertTrue(session.mediaUrl, session.mediaUrl.endsWith(".m3u8"))
        val body = get(session.mediaUrl).use { it.body.string() }
        val uris = proxiedUris(body)
        assertEquals(2, uris.size)
        uris.forEach { assertTrue(it, it.startsWith("http://127.0.0.1:${proxy.port}/s/${session.token}/u/")) }
        assertEquals(upstream.url("/v/seg2.ts?sig=abc").toString(), upstreamOf(uris[1]))
    }

    @Test fun followsRedirectBeforeRewriting() {
        route("/start.m3u8") {
            MockResponse
                .Builder()
                .code(302)
                .addHeader("Location", "/real/index.m3u8")
                .build()
        }
        route("/real/index.m3u8") { text(mediaPlaylist()) }
        val session = createHls("/start.m3u8")
        val body = get(session.mediaUrl).use { it.body.string() }
        assertEquals(upstream.url("/real/seg.ts").toString(), upstreamOf(proxiedUris(body)[0]))
        assertEquals(upstream.url("/real/seg2.ts?sig=abc").toString(), upstreamOf(proxiedUris(body)[1]))
    }

    @Test fun livePlaylistIsRefetchedEveryTime() {
        route("/live/index.m3u8") { text(mediaPlaylist(live = true)) }
        route("/live/seg.ts") { bytes(ts()) }
        val session = createHls("/live/index.m3u8")
        val before = hits.getValue("/live/index.m3u8").get()
        repeat(2) {
            get(session.mediaUrl).use {
                assertEquals(200, it.code)
                assertEquals("no-store", it.header("Cache-Control"))
                assertEquals("application/vnd.apple.mpegurl", it.header("Content-Type"))
            }
        }
        assertEquals(before + 2, hits.getValue("/live/index.m3u8").get())
    }

    @Test fun detectsLiveAndSegmentFormatOnCreate() {
        route("/m/master.m3u8") {
            text(
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\nhigh/index.m3u8\n",
            )
        }
        route("/m/low/index.m3u8") { text(mediaPlaylist(live = true)) }
        route("/m/low/seg.ts") { req ->
            assertEquals("bytes=0-65535", req.headers["Range"])
            bytes(ts())
        }
        val session = createHls("/m/master.m3u8")
        assertTrue(session.isLive)
        assertEquals(SegmentFormat.TS, session.hlsSegmentFormat)
        assertEquals("application/vnd.apple.mpegurl", session.contentType)
        assertNull(hits["/m/high/index.m3u8"])
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test fun stripsImagePrefixFromSegment() {
        val payload = ts(20)
        route("/d/index.m3u8") { text(mediaPlaylist(segment = "img/seg.png")) }
        route("/d/img/seg.png") { bytes(png + ByteArray(200) { 7 } + payload) }
        val session = createHls("/d/index.m3u8")
        assertEquals(SegmentFormat.TS, session.hlsSegmentFormat)
        val segmentUrl = proxiedUris(get(session.mediaUrl).use { it.body.string() })[0]
        assertTrue(segmentUrl, segmentUrl.endsWith(".ts"))
        get(segmentUrl).use {
            assertEquals(200, it.code)
            assertEquals("video/mp2t", it.header("Content-Type"))
            val body = it.body.bytes()
            assertEquals(0x47, body[0].toInt())
            assertTrue(payload.contentEquals(body))
        }
    }

    @Test fun passesRangeForDirectFiles() {
        val file = ByteArray(1000) { (it % 251).toByte() }
        route("/f/movie.mp4") { req ->
            val range = req.headers["Range"]
            val match = range?.let { Regex("bytes=(\\d+)-(\\d+)").find(it) }
            if (match == null) {
                MockResponse
                    .Builder()
                    .body(Buffer().write(file))
                    .addHeader("Content-Type", "video/mp4")
                    .build()
            } else {
                val (from, to) = match.destructured.toList().map { it.toInt() }
                MockResponse
                    .Builder()
                    .code(206)
                    .addHeader("Content-Type", "video/mp4")
                    .addHeader("Accept-Ranges", "bytes")
                    .addHeader("Content-Range", "bytes $from-$to/${file.size}")
                    .body(Buffer().write(file.copyOfRange(from, to + 1)))
                    .build()
            }
        }
        val session = runBlocking { proxy.createSession(candidate("/f/movie.mp4", MediaKind.FILE), emptyList()) }
        assertEquals("video/mp4", session.contentType)
        assertFalse(session.isLive)
        assertNull(session.hlsSegmentFormat)
        assertTrue(session.mediaUrl, session.mediaUrl.endsWith(".mp4"))
        get(session.mediaUrl) { header("Range", "bytes=100-199") }.use {
            assertEquals(206, it.code)
            assertEquals("bytes 100-199/1000", it.header("Content-Range"))
            assertEquals("bytes", it.header("Accept-Ranges"))
            assertEquals("100", it.header("Content-Length"))
            assertTrue(file.copyOfRange(100, 200).contentEquals(it.body.bytes()))
        }
    }

    @Test fun addsCorsAndAnswersOptions() {
        route("/c/index.m3u8") { text(mediaPlaylist()) }
        val session = createHls("/c/index.m3u8")
        val options =
            Request
                .Builder()
                .url(session.mediaUrl)
                .method("OPTIONS", null)
                .build()
        tv.newCall(options).execute().use {
            assertEquals(204, it.code)
            assertCors(it)
        }
        get(session.mediaUrl).use { assertCors(it) }
        get("http://127.0.0.1:${proxy.port}/nothing").use { assertCors(it) }
    }

    private fun assertCors(response: Response) {
        assertEquals("*", response.header("Access-Control-Allow-Origin"))
        assertEquals("*", response.header("Access-Control-Allow-Headers"))
        assertEquals("Content-Length, Content-Range", response.header("Access-Control-Expose-Headers"))
    }

    @Test fun unknownTokenIs404() {
        val url =
            ProxyUrls.build(
                "127.0.0.1",
                proxy.port,
                ProxyUrls.newToken(),
                ProxyUrls.newSecret(),
                upstream.url("/x.ts").toString(),
                "ts",
            )
        get(url).use { assertEquals(404, it.code) }
        assertEquals(0, upstream.requestCount)
    }

    @Test fun validTokenWithForgedUpstreamIs404() {
        route("/v/index.m3u8") { text(mediaPlaylist()) }
        val session = createHls("/v/index.m3u8")
        val before = upstream.requestCount
        val target = upstream.url("/internal/admin").toString()
        // A token holder hand-builds a URL: right token, but a MAC made with a key other than the session secret.
        val forged = ProxyUrls.build("127.0.0.1", proxy.port, session.token, ProxyUrls.newSecret(), target, "ts")
        get(forged).use { assertEquals(404, it.code) }
        // Swapping the upstream of a genuine URL while keeping its MAC is rejected as well.
        val genuine = proxiedUris(get(session.mediaUrl).use { it.body.string() })[0]
        val encodedTarget = Base64.getUrlEncoder().withoutPadding().encodeToString(target.toByteArray())
        val swapped = genuine.replace(Regex("/u/[A-Za-z0-9_-]+\\."), "/u/$encodedTarget.")
        get(swapped).use { assertEquals(404, it.code) }
        // Switching the extension of a genuine URL (to reach another handler) is rejected too.
        for (ext in listOf("vtt", "m3u8", "key")) {
            get(genuine.replace(Regex("\\.ts$"), ".$ext")).use { assertEquals(ext, 404, it.code) }
        }
        assertEquals(before + 1, upstream.requestCount) // only the genuine playlist fetch
        assertNull(hits["/internal/admin"])
    }

    @Test fun cookieOnlyGoesToTheCandidateHost() {
        val other = MockWebServer().apply { start() }
        try {
            // MockWebServer is reachable both as "localhost" and "127.0.0.1": use the other name for the foreign host.
            val otherHost = if (upstream.hostName == "127.0.0.1") "localhost" else "127.0.0.1"
            val foreign = { path: String -> "http://$otherHost:${other.port}$path" }
            val ts = ts()
            other.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = bytes(ts)
                }
            route("/k/index.m3u8") {
                text(
                    "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg.ts\n#EXTINF:6,\n" +
                        foreign("/cdn/seg2.ts") + "\n#EXTINF:6,\nhop.ts\n#EXT-X-ENDLIST\n",
                )
            }
            route("/k/seg.ts") { bytes(ts) }
            route(
                "/k/hop.ts",
            ) {
                MockResponse
                    .Builder()
                    .code(302)
                    .addHeader("Location", foreign("/cdn/hop.ts"))
                    .build()
            }
            val session = createHls("/k/index.m3u8")
            val uris = proxiedUris(get(session.mediaUrl).use { it.body.string() })
            uris.forEach { uri -> get(uri).use { assertEquals(200, it.code) } }

            assertEquals("sid=42", lastRequestTo("/k/seg.ts")?.headers?.get("Cookie"))
            val foreignRequests = generateSequence { other.takeRequest(0, TimeUnit.SECONDS) }.toList()
            assertEquals(listOf("/cdn/seg2.ts", "/cdn/hop.ts"), foreignRequests.map { it.url.encodedPath })
            foreignRequests.forEach {
                assertNull("Cookie leaked to ${it.url}", it.headers["Cookie"])
                assertEquals("https://page.example/", it.headers["Referer"])
                assertEquals("HomeCastTest/1.0", it.headers["User-Agent"])
            }
        } finally {
            other.close()
        }
    }

    @Test fun stripsLargeImagePrefixFromSegment() {
        val prefix = ByteArray(3 * 1024) { (it * 31).toByte() }.also { png.copyInto(it) }
        val payload = ts(20)
        route("/big/index.m3u8") { text(mediaPlaylist(segment = "seg.png")) }
        route("/big/seg.png") { bytes(prefix + payload) }
        val session = createHls("/big/index.m3u8")
        assertEquals(SegmentFormat.TS, session.hlsSegmentFormat)
        val segmentUrl = proxiedUris(get(session.mediaUrl).use { it.body.string() })[0]
        get(segmentUrl).use {
            assertEquals("video/mp2t", it.header("Content-Type"))
            assertTrue(payload.contentEquals(it.body.bytes()))
        }
    }

    @Test fun fmp4SegmentWithChanceSyncBytesIsServedUnmodified() {
        val segment = ByteArray(8192) { (it * 7).toByte() }
        "ftyp".toByteArray().copyInto(segment, 4)
        for (k in 0..2) segment[2000 + k * 188] = 0x47
        for (i in segment.indices) if (i < 2000 && segment[i].toInt() == 0x47) segment[i] = 0x48
        route("/f4/index.m3u8") { text(mediaPlaylist(segment = "seg.m4s")) }
        route("/f4/seg.m4s") { bytes(segment) }
        val session = createHls("/f4/index.m3u8")
        assertEquals(SegmentFormat.FMP4, session.hlsSegmentFormat)
        val segmentUrl = proxiedUris(get(session.mediaUrl).use { it.body.string() })[0]
        get(segmentUrl).use {
            assertEquals("video/mp4", it.header("Content-Type"))
            assertTrue(segment.contentEquals(it.body.bytes()))
        }
    }

    @Test fun subtitleRenditionSegmentsAreServedAsVtt() {
        route("/r/master.m3u8") {
            text(
                "#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"s\",NAME=\"TR\",URI=\"subs/tr.m3u8\"\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=800000,SUBTITLES=\"s\"\nv/index.m3u8\n",
            )
        }
        route("/r/v/index.m3u8") { text(mediaPlaylist()) }
        route("/r/v/seg.ts") { bytes(ts()) }
        route("/r/subs/tr.m3u8") { text("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nchunk_1\n#EXT-X-ENDLIST\n") }
        route("/r/subs/chunk_1") {
            bytes("1\n00:00:01,000 --> 00:00:02,000\ng\u00fcne\u015f\n".toByteArray(charset("windows-1254")))
        }
        val session = createHls("/r/master.m3u8")
        val master = get(session.mediaUrl).use { it.body.string() }
        val subtitlePlaylist = Regex("URI=\"([^\"]+)\"").find(master)!!.groupValues[1]
        val chunk = proxiedUris(get(subtitlePlaylist).use { it.body.string() })[0]
        assertTrue(chunk, chunk.endsWith(".vtt"))
        get(chunk).use {
            assertEquals(200, it.code)
            assertEquals("text/vtt; charset=utf-8", it.header("Content-Type"))
            val vtt = it.body.string()
            assertTrue(vtt, vtt.startsWith("WEBVTT"))
            assertTrue(vtt, vtt.contains("güneş"))
        }
    }

    @Test fun mapsUpstreamErrorsToReasons() {
        route("/e/403.m3u8") { text("", 403) }
        route("/e/401.mp4") { text("", 401) }
        route("/e/410.m3u8") { text("", 410) }
        route("/e/html.m3u8") { text("<html>nope</html>") }
        val closedPort = ServerSocket(0).use { it.localPort }
        val cases =
            listOf(
                candidate("/e/403.m3u8") to ProxyException.Reason.UPSTREAM_REJECTED,
                candidate("/e/401.mp4", MediaKind.FILE) to ProxyException.Reason.UPSTREAM_REJECTED,
                candidate("/e/missing.m3u8") to ProxyException.Reason.UPSTREAM_NOT_FOUND,
                candidate("/e/410.m3u8") to ProxyException.Reason.UPSTREAM_NOT_FOUND,
                candidate("/e/html.m3u8") to ProxyException.Reason.NOT_MEDIA,
                candidate("/e/manifest.mpd", MediaKind.DASH) to ProxyException.Reason.UNSUPPORTED_DASH,
                candidate("/", url = "http://127.0.0.1:$closedPort/index.m3u8") to
                    ProxyException.Reason.UPSTREAM_UNREACHABLE,
            )
        for ((c, reason) in cases) {
            try {
                runBlocking { proxy.createSession(c, emptyList()) }
                fail("expected $reason for ${c.url}")
            } catch (e: ProxyException) {
                assertEquals(c.url, reason, e.reason)
            }
        }
    }

    @Test fun thumbnailSpriteVttIsServedEmpty() {
        route("/s/index.m3u8") { text(mediaPlaylist()) }
        route("/s/en.vtt") { text("WEBVTT\n\n00:00.000 --> 00:05.000\nsprite.jpg#xywh=0,0,160,90\n") }
        val source = SubtitleSource("a", "en.vtt", null, SubtitleFormat.VTT, url = upstream.url("/s/en.vtt").toString())
        val session = runBlocking { proxy.createSession(candidate("/s/index.m3u8"), listOf(source)) }

        get(session.subtitles[0].url).use {
            assertEquals(200, it.code)
            val vtt = it.body.string()
            assertTrue(vtt, vtt.startsWith("WEBVTT"))
            assertFalse("thumbnail cues are not subtitles: $vtt", vtt.contains("xywh"))
            assertFalse(vtt, vtt.contains("-->"))
        }
    }

    @Test fun servesSrtSubtitleAsUtf8Vtt() {
        route("/s/index.m3u8") { text(mediaPlaylist()) }
        val srt = "1\r\n00:00:01,000 --> 00:00:02,500\r\nkış güneşi\r\n"
        route("/s/tr.srt") { bytes(srt.toByteArray(charset("windows-1254"))) }
        val sources =
            listOf(
                SubtitleSource("a", "Türkçe", "tr", SubtitleFormat.SRT, url = upstream.url("/s/tr.srt").toString()),
                SubtitleSource(
                    "b",
                    "English",
                    "en",
                    SubtitleFormat.VTT,
                    inlineVtt = "WEBVTT\n\n00:01.000 --> 00:02.000\nhello\n",
                ),
            )
        val session = runBlocking { proxy.createSession(candidate("/s/index.m3u8"), sources) }
        assertEquals(listOf(1, 2), session.subtitles.map { it.trackId })
        assertEquals("Türkçe", session.subtitles[0].label)
        assertEquals("tr", session.subtitles[0].language)
        assertNull(hits["/s/tr.srt"]) // fetched lazily
        get(session.subtitles[0].url).use {
            assertEquals(200, it.code)
            assertEquals("text/vtt; charset=utf-8", it.header("Content-Type"))
            val vtt = it.body.string()
            assertTrue(vtt, vtt.startsWith("WEBVTT"))
            assertTrue(vtt, vtt.contains("00:00:01.000 --> 00:00:02.500"))
            assertTrue(vtt, vtt.contains("ş"))
        }
        assertEquals(
            "Referer must be sent for subtitles",
            "https://page.example/",
            lastRequestTo("/s/tr.srt")?.headers?.get("Referer"),
        )
        get(session.subtitles[1].url).use { assertTrue(it.body.string().contains("hello")) }

        val added =
            proxy.addSubtitle(
                session.token,
                SubtitleSource("c", "Extra", null, SubtitleFormat.VTT, inlineVtt = "WEBVTT\n"),
            )
        assertEquals(3, added.trackId)
        assertTrue(added.url, added.url.endsWith(".vtt"))
        get(added.url).use { assertEquals(200, it.code) }
    }

    private fun lastRequestTo(path: String): RecordedRequest? =
        generateSequence { upstream.takeRequest(0, TimeUnit.SECONDS) }.lastOrNull { it.url.encodedPath == path }

    @Test fun clientDisconnectCancelsUpstream() {
        val big = ByteArray(10 * 1024 * 1024)
        route("/big/movie.mp4") { req ->
            if (req.headers["Range"] == "bytes=0-0") {
                MockResponse
                    .Builder()
                    .code(206)
                    .addHeader("Content-Type", "video/mp4")
                    .addHeader("Content-Range", "bytes 0-0/${big.size}")
                    .body(Buffer().write(big, 0, 1))
                    .build()
            } else {
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "video/mp4")
                    .body(Buffer().write(big))
                    .throttleBody(32 * 1024, 20, TimeUnit.MILLISECONDS)
                    .build()
            }
        }
        val session = runBlocking { proxy.createSession(candidate("/big/movie.mp4", MediaKind.FILE), emptyList()) }
        abortedUpstreamCalls.clear()
        repeat(20) { i ->
            val call = tv.newCall(Request.Builder().url(session.mediaUrl).build())
            val response = call.execute()
            assertEquals(200, response.code)
            assertEquals(
                1024,
                response.body
                    .source()
                    .readByteArray(1024)
                    .size,
            )
            call.cancel()
            response.close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (abortedUpstreamCalls.size <= i && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("upstream call #$i not cancelled within 2 s", abortedUpstreamCalls.size > i)
        }
        assertTrue(upstreamClient.connectionPool.connectionCount() <= 5)
    }

    @Test fun closeSessionInvalidatesToken() {
        route("/z/index.m3u8") { text(mediaPlaylist()) }
        val session = createHls("/z/index.m3u8")
        get(session.mediaUrl).use { assertEquals(200, it.code) }
        proxy.closeSession(session.token)
        get(session.mediaUrl).use { assertEquals(404, it.code) }

        val other = createHls("/z/index.m3u8")
        assertNotNull(other.token)
        assertFalse(other.token == session.token)
        proxy.closeAll()
        get(other.mediaUrl).use { assertEquals(404, it.code) }
    }
}
