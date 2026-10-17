package io.github.yunuserdemsihhatli.homecast.proxy

import io.github.yunuserdemsihhatli.homecast.capture.MediaKind
import io.github.yunuserdemsihhatli.homecast.capture.SubtitleSource
import io.github.yunuserdemsihhatli.homecast.capture.UpstreamHeaders
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ProxiedSubtitle(
    val trackId: Int,
    val url: String,
    val label: String,
    val language: String?,
)

/** What the Cast sender needs to LOAD a proxied video. */
data class ProxySession(
    val token: String,
    val mediaUrl: String,
    val contentType: String,
    val isLive: Boolean,
    val hlsSegmentFormat: SegmentFormat?,
    val subtitles: List<ProxiedSubtitle>,
)

class ProxyException(
    val reason: Reason,
    message: String,
) : Exception(message) {
    enum class Reason { UPSTREAM_REJECTED, UPSTREAM_NOT_FOUND, UPSTREAM_UNREACHABLE, NOT_MEDIA, UNSUPPORTED_DASH }
}

/** Server-side state behind a session token. */
internal class SessionState(
    val token: String,
    val kind: MediaKind,
    val mediaUpstream: String,
    val headers: UpstreamHeaders,
    val hlsSegmentFormat: SegmentFormat?,
) {
    /** Key for the per-URL MACs; never leaves the phone. */
    val secret: ByteArray = ProxyUrls.newSecret()

    /** The session's Cookie only goes to the candidate's exact scheme, host and port. */
    val cookieScope: CookieScope? = CookieScope.of(mediaUpstream)

    private val nextTrackId = AtomicInteger(1)
    val subtitles = ConcurrentHashMap<Int, SubtitleSource>()

    /** Converted WebVTT per track, filled lazily on the first request from the TV. */
    val vttCache = ConcurrentHashMap<Int, String>()

    /** Upstream URLs of in-manifest subtitle renditions; their segments are WebVTT whatever their extension. */
    val subtitlePlaylists: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun addSubtitle(source: SubtitleSource): Int = nextTrackId.getAndIncrement().also { subtitles[it] = source }

    companion object {
        /** Pseudo-upstream used in proxy URLs of external subtitle tracks. */
        const val TRACK_PREFIX = "track:"
    }
}
