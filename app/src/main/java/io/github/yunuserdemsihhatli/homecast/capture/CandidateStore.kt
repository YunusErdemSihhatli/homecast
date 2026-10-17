package io.github.yunuserdemsihhatli.homecast.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.net.URI

/**
 * Thread-safe holder of the videos and subtitles captured on the current page.
 *
 * [candidates] is ranked: HLS master (network-confirmed before script-only), HLS media playlist, FILE, DASH; newest
 * first within a rank.
 * Candidates are de-duplicated by URL without fragment. A non-master HLS candidate is hidden
 * (and never listed) while a master candidate exists with the same scheme and host whose
 * directory is a prefix of the variant's path.
 */
class CandidateStore(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _candidates = MutableStateFlow<List<VideoCandidate>>(emptyList())
    private val _subtitles = MutableStateFlow<List<SubtitleSource>>(emptyList())

    private var lastCaptureTime = Long.MIN_VALUE // guarded by this

    /** Directories of the page's HLS subtitle renditions (from [addSubtitlePlaylist]). */
    private val subtitlePlaylistDirs = MutableStateFlow<Set<String>>(emptySet())

    val candidates: StateFlow<List<VideoCandidate>> = _candidates
    val subtitles: StateFlow<List<SubtitleSource>> = _subtitles

    fun addVideo(
        url: String,
        kind: MediaKind,
        headers: UpstreamHeaders,
        pageUrl: String,
        pageTitle: String,
        isMaster: Boolean = false,
        fallbackHeaders: Boolean = false,
        networkConfirmed: Boolean = false,
    ) {
        val now = nextCaptureTime()
        val key = dedupKey(url)
        _candidates.update { current ->
            val existing = current.firstOrNull { dedupKey(it.url) == key }
            val updated =
                if (existing != null) {
                    val upgradeHeaders = existing.fallbackHeaders && !fallbackHeaders
                    val merged =
                        existing.copy(
                            isMaster = existing.isMaster || isMaster,
                            networkConfirmed = existing.networkConfirmed || networkConfirmed,
                            headers = if (upgradeHeaders) headers else existing.headers,
                            fallbackHeaders = existing.fallbackHeaders && fallbackHeaders,
                        )
                    current.map { if (it === existing) merged else it }
                } else {
                    current +
                        VideoCandidate(
                            url = url,
                            kind = kind,
                            headers = headers,
                            pageUrl = pageUrl,
                            pageTitle = pageTitle,
                            isMaster = isMaster,
                            capturedAtMillis = now,
                            fallbackHeaders = fallbackHeaders,
                            networkConfirmed = networkConfirmed,
                        )
                }
            rank(updated)
        }
    }

    /**
     * Adds a subtitle not captured yet. An already captured one is left as it is (its showing state included), except
     * that the page's own `<track>` report replaces a copy only seen on the network (it has the real label).
     *
     * Not everything that looks like a `.vtt` is a subtitle file: a URL that names a thumbnail track is never
     * captured, and a network-only `.vtt` that looks like a segment of an HLS subtitle rendition (numbered, named
     * seg/segment/chunk/frag, or next to a captured media playlist) is skipped; the TV gets those renditions from the
     * HLS manifest itself. Page-reported subtitles are listed first. At most [MAX_SUBTITLES] are kept per page, the
     * oldest network-only one making room first.
     */
    fun addSubtitle(source: SubtitleSource) {
        val url = source.url
        if (url != null && SubtitleUrls.isThumbnailTrack(url)) return
        if (url != null && !source.reportedByPage && looksLikeSegment(url)) return
        _subtitles.update { current ->
            val existing = current.firstOrNull { it.id == source.id }
            val next =
                when {
                    existing == null -> {
                        current + source
                    }

                    source.reportedByPage && !existing.reportedByPage -> {
                        current.map { if (it === existing) source.copy(showing = existing.showing) else it }
                    }

                    else -> {
                        return@update current
                    }
                }
            capped(next.sortedByDescending { it.reportedByPage }) // stable: capture order within each group
        }
    }

    /**
     * [url] is an HLS subtitle rendition playlist (page script saw its WebVTT segments, or a master naming it). It is
     * not a video, so it is no longer listed, but only if the candidate was captured from [reporterOrigin] (the
     * frame Referer it was seen with): a report never removes a video found by another origin. Network-only `.vtt`
     * files in its directory are its segments.
     */
    fun addSubtitlePlaylist(
        url: String,
        reporterOrigin: String?,
    ) {
        val dir = SubtitleUrls.directoryOf(url) ?: return
        subtitlePlaylistDirs.update { it + dir }
        val key = dedupKey(url)
        val reporter = reporterOrigin?.let(::originOf)
        _candidates.update { current ->
            current.filterNot {
                dedupKey(it.url) == key && reporter != null &&
                    it.headers.referer?.let(::originOf) == reporter
            }
        }
        _subtitles.update { current ->
            current.filterNot { !it.reportedByPage && it.url?.let(SubtitleUrls::directoryOf) == dir }
        }
    }

    private fun looksLikeSegment(url: String): Boolean =
        SubtitleUrls.hasSegmentName(url) || SubtitleUrls.directoryOf(url) in subtitlePlaylistDirs.value

    private fun capped(list: List<SubtitleSource>): List<SubtitleSource> {
        var result = list
        while (result.size > MAX_SUBTITLES) {
            val victim = result.firstOrNull { !it.reportedByPage && it.url != null } ?: result.first()
            result = result - victim
        }
        return result
    }

    /** Records whether the page shows subtitle [id]; only the page's own report (page script) knows this. */
    fun setSubtitleShowing(
        id: String,
        showing: Boolean,
    ) {
        _subtitles.update { current ->
            current.map {
                if (it.id == id &&
                    it.showing != showing
                ) {
                    it.copy(showing = showing)
                } else {
                    it
                }
            }
        }
    }

    /** Flags an already captured playlist as a master playlist (learned after it was parsed). */
    fun markMaster(url: String) {
        val key = dedupKey(url)
        _candidates.update { current ->
            rank(current.map { if (dedupKey(it.url) == key) it.copy(isMaster = true) else it })
        }
    }

    /** The clock, made strictly increasing so the newest of two captures in the same millisecond still ranks first. */
    private fun nextCaptureTime(): Long =
        synchronized(this) {
            maxOf(clock(), lastCaptureTime + 1).also { lastCaptureTime = it }
        }

    fun onPageChanged(newPageUrl: String) {
        _candidates.value = emptyList()
        _subtitles.value = emptyList()
        subtitlePlaylistDirs.value = emptySet()
    }

    private fun dedupKey(url: String) = url.substringBefore('#')

    /** `scheme://host[:port]` (default ports dropped), or null for anything that is not an absolute URL. */
    private fun originOf(url: String): String? =
        runCatching { URI(url) }.getOrNull()?.let { u ->
            val scheme = u.scheme?.lowercase() ?: return null
            val host = u.host?.lowercase() ?: return null
            val defaultPort = (scheme == "http" && u.port == 80) || (scheme == "https" && u.port == 443)
            if (u.port == -1 || defaultPort) "$scheme://$host" else "$scheme://$host:${u.port}"
        }

    companion object {
        /** Most subtitles kept per page (and, by the Cast LOAD, sent to the TV). */
        const val MAX_SUBTITLES = 20
    }

    private fun rank(list: List<VideoCandidate>): List<VideoCandidate> {
        val masters = list.filter { it.kind == MediaKind.HLS && it.isMaster }
        return list
            .filterNot { c -> c.kind == MediaKind.HLS && !c.isMaster && masters.any { isVariantOf(c.url, it.url) } }
            .sortedWith(compareBy<VideoCandidate> { rankOf(it) }.thenByDescending { it.capturedAtMillis })
    }

    private fun rankOf(c: VideoCandidate) =
        when (c.kind) {
            MediaKind.HLS -> if (c.isMaster) (if (c.networkConfirmed) 0 else 1) else 2
            MediaKind.FILE -> 3
            MediaKind.DASH -> 4
        }

    private fun isVariantOf(
        variantUrl: String,
        masterUrl: String,
    ): Boolean {
        val v = parse(variantUrl) ?: return false
        val m = parse(masterUrl) ?: return false
        val masterDir = m.path.substringBeforeLast('/', "") + "/"
        return v.scheme.equals(m.scheme, ignoreCase = true) &&
            v.host.equals(m.host, ignoreCase = true) &&
            v.path.startsWith(masterDir)
    }

    private fun parse(url: String): ParsedUrl? =
        runCatching { URI(url) }.getOrNull()?.let { u ->
            val scheme = u.scheme
            val host = u.host
            if (scheme == null || host == null) null else ParsedUrl(scheme, host, u.path.orEmpty())
        }

    private data class ParsedUrl(
        val scheme: String,
        val host: String,
        val path: String,
    )
}
