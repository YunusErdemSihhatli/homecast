package io.github.yunuserdemsihhatli.homecast.capture

enum class MediaKind { HLS, FILE, DASH }

enum class SubtitleFormat { VTT, SRT }

data class UpstreamHeaders(
    val referer: String?,
    val origin: String?,
    val userAgent: String,
    val cookie: String?,
)

data class VideoCandidate(
    val url: String,
    val kind: MediaKind,
    val headers: UpstreamHeaders,
    val pageUrl: String,
    val pageTitle: String,
    val isMaster: Boolean = false,
    val capturedAtMillis: Long,
    /** [headers] carry a guessed Referer (the top page): headers seen later for the same URL replace them. */
    val fallbackHeaders: Boolean = false,
    /** The WebView itself requested this URL; a candidate reported only by page script ranks lower. */
    val networkConfirmed: Boolean = false,
)

data class SubtitleSource(
    val id: String,
    val label: String,
    val language: String?,
    val format: SubtitleFormat,
    val url: String? = null,
    val headers: UpstreamHeaders? = null,
    val inlineVtt: String? = null,
    /** The page is currently showing this track, so it is switched on when playback moves to the TV. */
    val showing: Boolean = false,
    /** Reported by the page's player as a `<track>` (labelled, a real subtitle), not only seen on the network. */
    val reportedByPage: Boolean = false,
)
