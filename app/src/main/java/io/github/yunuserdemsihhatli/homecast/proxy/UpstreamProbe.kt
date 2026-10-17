package io.github.yunuserdemsihhatli.homecast.proxy

import io.github.yunuserdemsihhatli.homecast.capture.MediaKind
import io.github.yunuserdemsihhatli.homecast.capture.UpstreamHeaders
import io.github.yunuserdemsihhatli.homecast.capture.VideoCandidate
import io.github.yunuserdemsihhatli.homecast.hls.HlsRewriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

/** Inspects a candidate before casting: is it reachable and playable, is it live, which segment format. */
internal class UpstreamProbe(
    private val client: OkHttpClient,
) {
    class Result(
        val ext: String,
        val contentType: String,
        val isLive: Boolean,
        val segmentFormat: SegmentFormat?,
    )

    /** @throws ProxyException when the candidate cannot be proxied. */
    suspend fun probe(
        candidate: VideoCandidate,
        cookieScope: CookieScope?,
    ): Result {
        val upstream = Upstream(candidate.headers, cookieScope)
        return when (candidate.kind) {
            MediaKind.DASH -> throw ProxyException(ProxyException.Reason.UNSUPPORTED_DASH, "DASH is not supported")
            MediaKind.HLS -> probeHls(candidate.url, upstream)
            MediaKind.FILE -> probeFile(candidate.url, upstream)
        }
    }

    private class Upstream(
        val headers: UpstreamHeaders,
        val cookieScope: CookieScope?,
    )

    private suspend fun probeHls(
        url: String,
        upstream: Upstream,
    ): Result {
        var (body, base) = fetchPlaylist(url, upstream)
        if (HlsRewriter.isMaster(body)) {
            val variant = HlsRewriter.firstSegmentUrl(body, base) ?: throw notMedia(url)
            fetchPlaylist(variant, upstream).let { (variantBody, variantBase) ->
                body = variantBody
                base = variantBase
            }
        }
        val format = HlsRewriter.firstSegmentUrl(body, base)?.let { segmentFormat(it, upstream) }
        return Result(MediaTypes.EXT_PLAYLIST, MediaTypes.TYPE_PLAYLIST, HlsRewriter.isLive(body), format)
    }

    private suspend fun probeFile(
        url: String,
        upstream: Upstream,
    ): Result {
        val ext = MediaTypes.fileExtension(url)
        val upstreamType = fetch(url, upstream, range = "bytes=0-0").use { it.header("Content-Type") }
        if (upstreamType != null && upstreamType.startsWith("text/html", ignoreCase = true)) throw notMedia(url)
        return Result(ext, MediaTypes.fileContentType(upstreamType, ext), isLive = false, segmentFormat = null)
    }

    /** Returns the playlist text and the final (post-redirect) URL to resolve its URIs against. */
    private suspend fun fetchPlaylist(
        url: String,
        upstream: Upstream,
    ): Pair<String, String> =
        fetch(url, upstream).use { response ->
            val text = io(url) { readPlaylist(response.body.source()) } ?: throw notMedia(url)
            text to response.request.url.toString()
        }

    private suspend fun segmentFormat(
        url: String,
        upstream: Upstream,
    ): SegmentFormat? =
        try {
            fetch(url, upstream, range = "bytes=0-${SNIFF_BYTES - 1}").use { response ->
                io(url) { SegmentSniffer.detect(peekHead(response.body.source())) }
            }
        } catch (_: ProxyException) {
            null // best effort: playback may still work without an explicit segment format
        }

    private suspend fun fetch(
        url: String,
        upstream: Upstream,
        range: String? = null,
    ): Response {
        val request =
            upstreamRequest(url, upstream.headers, upstream.cookieScope, range, identity = range != null)
                ?: throw notMedia(url)
        val response = io(url) { client.newCall(request).await() }
        if (response.isSuccessful) return response
        response.close()
        val reason =
            when (response.code) {
                401, 403 -> ProxyException.Reason.UPSTREAM_REJECTED
                404, 410 -> ProxyException.Reason.UPSTREAM_NOT_FOUND
                in 400..499 -> ProxyException.Reason.UPSTREAM_REJECTED
                else -> ProxyException.Reason.UPSTREAM_UNREACHABLE
            }
        throw ProxyException(reason, "Upstream answered HTTP ${response.code} for $url")
    }

    private suspend fun <T> io(
        url: String,
        block: suspend () -> T,
    ): T =
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: IOException) {
            throw ProxyException(ProxyException.Reason.UPSTREAM_UNREACHABLE, "Cannot reach $url: ${e.message}")
        }

    private fun notMedia(url: String) = ProxyException(ProxyException.Reason.NOT_MEDIA, "Not a playable stream: $url")
}
