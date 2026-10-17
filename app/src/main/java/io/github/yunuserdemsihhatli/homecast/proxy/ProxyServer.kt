package io.github.yunuserdemsihhatli.homecast.proxy

import io.github.yunuserdemsihhatli.homecast.capture.MediaKind
import io.github.yunuserdemsihhatli.homecast.capture.SubtitleSource
import io.github.yunuserdemsihhatli.homecast.capture.UpstreamHeaders
import io.github.yunuserdemsihhatli.homecast.capture.VideoCandidate
import io.github.yunuserdemsihhatli.homecast.hls.HlsRewriter
import io.github.yunuserdemsihhatli.homecast.hls.HlsUriKind
import io.github.yunuserdemsihhatli.homecast.proxy.MediaTypes.EXT_INIT
import io.github.yunuserdemsihhatli.homecast.proxy.MediaTypes.EXT_KEY
import io.github.yunuserdemsihhatli.homecast.proxy.MediaTypes.EXT_PLAYLIST
import io.github.yunuserdemsihhatli.homecast.proxy.MediaTypes.EXT_VTT
import io.github.yunuserdemsihhatli.homecast.proxy.MediaTypes.TYPE_PLAYLIST
import io.github.yunuserdemsihhatli.homecast.proxy.MediaTypes.TYPE_VTT
import io.github.yunuserdemsihhatli.homecast.subtitles.SrtToVtt
import io.github.yunuserdemsihhatli.homecast.subtitles.SubtitleDecoder
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.util.cio.ChannelIOException
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local HTTP proxy the Cast device fetches media from.
 *
 * Each request is forwarded upstream with the headers the video server expects, HLS playlists are rewritten so
 * every URI points back here, disguised segments are un-disguised, and CORS headers are added for the receiver.
 * Only upstream URLs this proxy signed for a session are fetched. All public methods are thread-safe.
 */
class ProxyServer(
    client: OkHttpClient,
) {
    private val client = client.withCookieScoping()
    private val probe = UpstreamProbe(this.client)
    private val sessions = ConcurrentHashMap<String, SessionState>()
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Volatile
    private var advertisedHost = "127.0.0.1"

    @Volatile
    var port: Int = 0
        private set

    /** Starts listening on a random free port; does nothing when already started. */
    @Synchronized
    fun start(bindHost: String = "0.0.0.0") {
        if (server != null) return
        val started =
            embeddedServer(CIO, port = 0, host = bindHost) {
                intercept(ApplicationCallPipeline.Call) { handle(call) }
            }.start(wait = false)
        server = started
        port =
            runBlocking {
                started.engine
                    .resolvedConnectors()
                    .first()
                    .port
            }
    }

    /** Stops the server; blocks for up to [STOP_TIMEOUT_MILLIS], so call it off the main thread. */
    @Synchronized
    fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MILLIS)
        server = null
        port = 0
    }

    /** The phone's Wi-Fi IPv4 address, which is what the Cast device must use to reach this proxy. */
    fun setAdvertisedHost(host: String) {
        advertisedHost = host
    }

    suspend fun createSession(
        candidate: VideoCandidate,
        subtitles: List<SubtitleSource>,
    ): ProxySession {
        check(port > 0) { "Proxy server is not started" }
        val token = ProxyUrls.newToken()
        val cookieScope = CookieScope.of(candidate.url)
        val result = probe.probe(candidate, cookieScope)
        val state = SessionState(token, candidate.kind, candidate.url, candidate.headers, result.segmentFormat)
        sessions[token] = state
        return ProxySession(
            token = token,
            mediaUrl = proxyUrl(state, candidate.url, result.ext),
            contentType = result.contentType,
            isLive = result.isLive,
            hlsSegmentFormat = result.segmentFormat,
            subtitles = subtitles.map { addSubtitle(state, it) },
        )
    }

    fun addSubtitle(
        token: String,
        source: SubtitleSource,
    ): ProxiedSubtitle {
        val state = requireNotNull(sessions[token]) { "Unknown proxy session" }
        return addSubtitle(state, source)
    }

    fun closeSession(token: String) {
        sessions.remove(token)
    }

    fun closeAll() {
        sessions.clear()
    }

    private fun addSubtitle(
        state: SessionState,
        source: SubtitleSource,
    ): ProxiedSubtitle {
        val trackId = state.addSubtitle(source)
        val url = proxyUrl(state, SessionState.TRACK_PREFIX + trackId, EXT_VTT)
        return ProxiedSubtitle(trackId, url, source.label, source.language)
    }

    private fun proxyUrl(
        state: SessionState,
        upstream: String,
        ext: String,
    ): String = ProxyUrls.build(advertisedHost, port, state.token, state.secret, upstream, ext)

    // ---- Request handling ------------------------------------------------------------------------------------

    private suspend fun handle(call: ApplicationCall) {
        call.response.header("Access-Control-Allow-Origin", "*")
        call.response.header("Access-Control-Allow-Headers", "*")
        call.response.header("Access-Control-Expose-Headers", "Content-Length, Content-Range")
        when (call.request.httpMethod) {
            HttpMethod.Options -> {
                call.response.header("Access-Control-Allow-Methods", "GET, OPTIONS")
                call.respond(HttpStatusCode.NoContent)
            }

            HttpMethod.Get -> {
                serve(call)
            }

            else -> {
                call.respond(HttpStatusCode.MethodNotAllowed)
            }
        }
    }

    private suspend fun serve(call: ApplicationCall) {
        val path = ProxyUrls.parse(call.request.path())
        val session = path?.let { sessions[it.token] }
        if (path == null || session == null || !ProxyUrls.verify(session.secret, path)) {
            notFound(call)
            return
        }
        val upstream = path.upstream
        try {
            when {
                upstream.startsWith(SessionState.TRACK_PREFIX) -> {
                    serveTrack(call, session, upstream)
                }

                session.kind == MediaKind.FILE && upstream == session.mediaUpstream -> {
                    serveFile(
                        call,
                        session,
                        upstream,
                    )
                }

                path.ext == EXT_PLAYLIST -> {
                    servePlaylist(call, session, upstream)
                }

                path.ext == EXT_VTT -> {
                    serveSubtitleSegment(call, session, upstream)
                }

                path.ext in MediaTypes.SEGMENT_TYPES -> {
                    serveSegment(call, session, upstream, path.ext)
                }

                else -> {
                    servePassthrough(call, session, upstream, path.ext)
                }
            }
        } catch (e: IOException) {
            // Upstream failed before anything was sent: tell the TV. Once streaming has started the failure (upstream
            // broke or the TV went away) has already aborted the response, and respondStream released the upstream.
            if (!call.response.isSent) {
                call.respond(HttpStatusCode.BadGateway)
            } else if (e !is ChannelIOException) {
                throw e
            }
        }
    }

    private suspend fun servePlaylist(
        call: ApplicationCall,
        session: SessionState,
        upstream: String,
    ) {
        val response = fetch(call, session.headers, session.cookieScope, upstream) ?: return
        val subtitleRendition = upstream in session.subtitlePlaylists
        val body =
            response.use {
                withContext(Dispatchers.IO) { readPlaylist(it.body.source()) }?.let { text ->
                    HlsRewriter.rewrite(text, it.request.url.toString()) { url, kind ->
                        rewrittenUri(session, url, kind, subtitleRendition)
                    }
                }
            }
        if (body == null) {
            call.respond(HttpStatusCode.BadGateway)
            return
        }
        call.response.header("Cache-Control", "no-store")
        call.respondBytes(body.toByteArray(Charsets.UTF_8), ContentType.parse(TYPE_PLAYLIST))
    }

    private fun rewrittenUri(
        session: SessionState,
        url: String,
        kind: HlsUriKind,
        inSubtitleRendition: Boolean,
    ): String {
        val ext =
            when (kind) {
                HlsUriKind.PLAYLIST -> {
                    EXT_PLAYLIST
                }

                HlsUriKind.SUBTITLE_PLAYLIST -> {
                    session.subtitlePlaylists += url
                    EXT_PLAYLIST
                }

                HlsUriKind.KEY -> {
                    EXT_KEY
                }

                HlsUriKind.INIT -> {
                    EXT_INIT
                }

                HlsUriKind.SEGMENT -> {
                    if (inSubtitleRendition) EXT_VTT else MediaTypes.segmentExtension(url, session.hlsSegmentFormat)
                }
            }
        return proxyUrl(session, url, ext)
    }

    private suspend fun serveSegment(
        call: ApplicationCall,
        session: SessionState,
        upstream: String,
        ext: String,
    ) {
        val range = call.request.headers["Range"]
        val upstreamCall = newCall(session.headers, session.cookieScope, upstream, range) ?: return notFound(call)
        val response = upstreamCall.await()
        if (!response.isSuccessful) return passStatus(call, response)
        var contentType = MediaTypes.SEGMENT_TYPES.getValue(ext)
        var skip = 0
        if (range == null) {
            // Byte-range requests are passed through untouched; whole segments are sniffed and un-disguised.
            val source = response.body.source()
            val head =
                try {
                    withContext(Dispatchers.IO) { peekHead(source) }
                } catch (e: IOException) {
                    response.close()
                    throw e
                }
            SegmentSniffer.detect(head)?.let { format ->
                contentType = MediaTypes.segmentType(format)
                if (format == SegmentFormat.TS) skip = SegmentSniffer.tsStartOffset(head)
            }
            source.skip(skip.toLong()) // already buffered by peekHead
        }
        val length =
            response.body
                .contentLength()
                .takeIf { it >= 0 }
                ?.minus(skip)
        response.header("Content-Range")?.let { call.response.header("Content-Range", it) }
        respondStream(call, upstreamCall, response, contentType, length)
    }

    private suspend fun servePassthrough(
        call: ApplicationCall,
        session: SessionState,
        upstream: String,
        ext: String,
    ) {
        val range = call.request.headers["Range"]
        val upstreamCall = newCall(session.headers, session.cookieScope, upstream, range) ?: return notFound(call)
        val response = upstreamCall.await()
        if (!response.isSuccessful) return passStatus(call, response)
        val contentType = MediaTypes.PASSTHROUGH_TYPES[ext] ?: MediaTypes.TYPE_BINARY
        response.header("Content-Range")?.let { call.response.header("Content-Range", it) }
        respondStream(call, upstreamCall, response, contentType, response.body.contentLength().takeIf { it >= 0 })
    }

    private suspend fun serveFile(
        call: ApplicationCall,
        session: SessionState,
        upstream: String,
    ) {
        val range = call.request.headers["Range"]
        val upstreamCall = newCall(session.headers, session.cookieScope, upstream, range) ?: return notFound(call)
        val response = upstreamCall.await()
        if (!response.isSuccessful) return passStatus(call, response)
        for (name in listOf("Content-Range", "Accept-Ranges")) {
            response.header(name)?.let { call.response.header(name, it) }
        }
        val contentType =
            MediaTypes.fileContentType(
                response.header("Content-Type"),
                MediaTypes.fileExtension(upstream),
            )
        respondStream(call, upstreamCall, response, contentType, response.body.contentLength().takeIf { it >= 0 })
    }

    private suspend fun serveTrack(
        call: ApplicationCall,
        session: SessionState,
        upstream: String,
    ) {
        val trackId = upstream.removePrefix(SessionState.TRACK_PREFIX).toIntOrNull()
        val source = trackId?.let { session.subtitles[it] } ?: return notFound(call)
        val vtt =
            session.vttCache[trackId] ?: run {
                val text =
                    source.inlineVtt ?: run {
                        val url = source.url ?: return notFound(call)
                        val (headers, scope) =
                            source.headers?.let { it to CookieScope.of(url) }
                                ?: (session.headers to session.cookieScope)
                        fetchText(call, headers, scope, url) ?: return
                    }
                // A thumbnail track (sprite coordinates) is WebVTT but no subtitle: the TV gets an empty track.
                val vtt = if (THUMBNAIL_CUE in text) EMPTY_VTT else SrtToVtt.ensureVtt(text)
                vtt.also { session.vttCache[trackId] = it }
            }
        call.respondBytes(vtt.toByteArray(Charsets.UTF_8), ContentType.parse(TYPE_VTT))
    }

    /** WebVTT segments referenced by an in-manifest subtitle playlist. */
    private suspend fun serveSubtitleSegment(
        call: ApplicationCall,
        session: SessionState,
        upstream: String,
    ) {
        val text = fetchText(call, session.headers, session.cookieScope, upstream) ?: return
        call.respondBytes(SrtToVtt.ensureVtt(text).toByteArray(Charsets.UTF_8), ContentType.parse(TYPE_VTT))
    }

    /** Fetches and decodes a subtitle file; responds with the error itself and returns null on failure. */
    private suspend fun fetchText(
        call: ApplicationCall,
        headers: UpstreamHeaders,
        cookieScope: CookieScope?,
        url: String,
    ): String? {
        val response = fetch(call, headers, cookieScope, url) ?: return null
        return response.use {
            withContext(Dispatchers.IO) { SubtitleDecoder.decode(readText(it.body.source())) }
        }
    }

    /** Executes an upstream GET for a text body; on a non-2xx answer passes the status to the TV and returns null. */
    private suspend fun fetch(
        call: ApplicationCall,
        headers: UpstreamHeaders,
        cookieScope: CookieScope?,
        url: String,
    ): Response? {
        val request = upstreamRequest(url, headers, cookieScope)
        if (request == null) {
            notFound(call)
            return null
        }
        val response = client.newCall(request).await()
        if (response.isSuccessful) return response
        passStatus(call, response)
        return null
    }

    /** A media GET (byte-exact, Range passed through), or null when [url] is not http(s). */
    private fun newCall(
        headers: UpstreamHeaders,
        cookieScope: CookieScope?,
        url: String,
        range: String?,
    ): Call? = upstreamRequest(url, headers, cookieScope, range, identity = true)?.let(client::newCall)

    private suspend fun notFound(call: ApplicationCall) = call.respond(HttpStatusCode.NotFound)

    private suspend fun passStatus(
        call: ApplicationCall,
        response: Response,
    ) {
        response.close()
        call.respond(HttpStatusCode.fromValue(response.code))
    }

    /**
     * Streams [response] to the TV. If the TV goes away mid-stream (seek, stop) the upstream call is cancelled so
     * the connection and its thread are released instead of downloading the rest of the body.
     */
    private suspend fun respondStream(
        call: ApplicationCall,
        upstreamCall: Call,
        response: Response,
        contentType: String,
        contentLength: Long?,
    ) {
        val completed = AtomicBoolean(false)
        val cancelOnAbort =
            currentCoroutineContext().job.invokeOnCompletion {
                if (!completed.get()) upstreamCall.cancel()
            }
        try {
            val source = response.body.source()
            call.respondBytesWriter(
                parseContentType(contentType),
                HttpStatusCode.fromValue(response.code),
                contentLength,
            ) {
                val buffer = ByteArray(STREAM_CHUNK)
                while (true) {
                    val read = withContext(Dispatchers.IO) { source.read(buffer) }
                    if (read == -1) break
                    writeFully(buffer, 0, read)
                    flush()
                }
                completed.set(true)
            }
        } finally {
            cancelOnAbort.dispose()
            if (!completed.get()) upstreamCall.cancel()
            response.close()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 1_000L
        const val THUMBNAIL_CUE = "#xywh="
        const val EMPTY_VTT = "WEBVTT\n\n"
        const val STREAM_CHUNK = 64 * 1024

        fun parseContentType(value: String): ContentType =
            try {
                ContentType.parse(value)
            } catch (_: Exception) {
                ContentType.Application.OctetStream
            }
    }
}
