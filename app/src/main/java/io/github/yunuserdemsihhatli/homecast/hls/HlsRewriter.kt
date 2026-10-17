package io.github.yunuserdemsihhatli.homecast.hls

import okhttp3.HttpUrl.Companion.toHttpUrl

enum class HlsUriKind { PLAYLIST, SEGMENT, KEY, INIT, SUBTITLE_PLAYLIST }

/** Rewrites every URI of an HLS playlist through a caller-supplied mapper (the local proxy). */
object HlsRewriter {
    private val uriAttribute = Regex("URI=\"([^\"]*)\"")
    private val uriTags =
        listOf(
            "#EXT-X-KEY",
            "#EXT-X-SESSION-KEY",
            "#EXT-X-MAP",
            "#EXT-X-MEDIA",
            "#EXT-X-I-FRAME-STREAM-INF",
        )
    private const val STREAM_INF = "#EXT-X-STREAM-INF"

    fun isPlaylist(body: String): Boolean = body.trim().startsWith("#EXTM3U")

    fun isMaster(body: String): Boolean = body.contains(STREAM_INF)

    fun isLive(body: String): Boolean =
        isPlaylist(body) &&
            !isMaster(body) &&
            !body.contains("#EXT-X-ENDLIST") &&
            !body.contains("#EXT-X-PLAYLIST-TYPE:VOD")

    fun rewrite(
        body: String,
        baseUrl: String,
        toProxy: (absoluteUrl: String, kind: HlsUriKind) -> String,
    ): String {
        val base = baseUrl.toHttpUrl()
        var afterStreamInf = false
        return body.split("\n").joinToString("\n") { rawLine ->
            val line = rawLine.trimEnd('\r')
            val eol = rawLine.substring(line.length)
            val rewritten =
                when {
                    line.isBlank() -> {
                        line
                    }

                    line.startsWith("#") -> {
                        afterStreamInf = line.startsWith(STREAM_INF)
                        rewriteTag(line, base, toProxy)
                    }

                    else -> {
                        val kind = if (afterStreamInf) HlsUriKind.PLAYLIST else HlsUriKind.SEGMENT
                        afterStreamInf = false
                        resolve(base, line.trim())?.let { toProxy(it, kind) } ?: line
                    }
                }
            rewritten + eol
        }
    }

    fun firstSegmentUrl(
        body: String,
        baseUrl: String,
    ): String? {
        val base = baseUrl.toHttpUrl()
        return body
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?.let { resolve(base, it) }
    }

    private fun rewriteTag(
        line: String,
        base: okhttp3.HttpUrl,
        toProxy: (String, HlsUriKind) -> String,
    ): String {
        val tag = uriTags.firstOrNull { line.startsWith("$it:") } ?: return line
        val kind =
            when (tag) {
                "#EXT-X-KEY", "#EXT-X-SESSION-KEY" -> {
                    HlsUriKind.KEY
                }

                "#EXT-X-MAP" -> {
                    HlsUriKind.INIT
                }

                "#EXT-X-MEDIA" -> {
                    if (line.contains("TYPE=SUBTITLES")) HlsUriKind.SUBTITLE_PLAYLIST else HlsUriKind.PLAYLIST
                }

                else -> {
                    HlsUriKind.PLAYLIST
                }
            }
        return uriAttribute.replace(line) { match ->
            val absolute = resolve(base, match.groupValues[1])
            if (absolute == null) match.value else "URI=\"${toProxy(absolute, kind)}\""
        }
    }

    private fun resolve(
        base: okhttp3.HttpUrl,
        uri: String,
    ): String? {
        val lower = uri.lowercase()
        if (uri.isEmpty() || lower.startsWith("data:") || lower.startsWith("skd:")) return null
        return base.resolve(uri)?.toString()
    }
}
