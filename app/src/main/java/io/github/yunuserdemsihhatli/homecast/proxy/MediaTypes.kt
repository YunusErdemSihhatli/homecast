package io.github.yunuserdemsihhatli.homecast.proxy

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Proxy URL extensions and the content types the Cast receiver gets for them. */
internal object MediaTypes {
    const val EXT_PLAYLIST = "m3u8"
    const val EXT_TS = "ts"
    const val EXT_FMP4 = "m4s"
    const val EXT_AAC = "aac"
    const val EXT_KEY = "key"
    const val EXT_INIT = "mp4"
    const val EXT_VTT = "vtt"
    private const val DEFAULT_FILE_EXT = "mp4"

    const val TYPE_PLAYLIST = "application/vnd.apple.mpegurl"
    const val TYPE_VTT = "text/vtt; charset=utf-8"
    const val TYPE_BINARY = "application/octet-stream"

    val SEGMENT_TYPES = mapOf(EXT_TS to "video/mp2t", EXT_FMP4 to "video/mp4", EXT_AAC to "audio/aac")
    val PASSTHROUGH_TYPES = mapOf(EXT_KEY to TYPE_BINARY, EXT_INIT to "video/mp4")
    private val FILE_TYPES =
        mapOf(
            "mp4" to "video/mp4",
            "m4v" to "video/mp4",
            "mov" to "video/quicktime",
            "webm" to "video/webm",
            "mkv" to "video/x-matroska",
            "ogv" to "video/ogg",
            "mp3" to "audio/mpeg",
            "m4a" to "audio/mp4",
            "aac" to "audio/aac",
            "ogg" to "audio/ogg",
        )
    private val GENERIC_TYPES = setOf("application/octet-stream", "binary/octet-stream", "text/plain")

    fun segmentType(format: SegmentFormat): String =
        when (format) {
            SegmentFormat.TS -> SEGMENT_TYPES.getValue(EXT_TS)
            SegmentFormat.FMP4 -> SEGMENT_TYPES.getValue(EXT_FMP4)
            SegmentFormat.AAC -> SEGMENT_TYPES.getValue(EXT_AAC)
        }

    fun pathExtension(url: String): String =
        url
            .toHttpUrlOrNull()
            ?.encodedPath
            ?.substringAfterLast('/')
            ?.substringAfterLast('.', "")
            ?.lowercase()
            .orEmpty()

    /** Extension for a direct media file URL; unknown extensions become `mp4`. */
    fun fileExtension(url: String): String = pathExtension(url).takeIf { it in FILE_TYPES } ?: DEFAULT_FILE_EXT

    /** Keeps the upstream content type unless it is missing or generic, then falls back by extension. */
    fun fileContentType(
        upstream: String?,
        ext: String,
    ): String {
        val mime = upstream?.substringBefore(';')?.trim()?.lowercase()
        return if (mime.isNullOrEmpty() || mime in GENERIC_TYPES) FILE_TYPES.getValue(ext) else upstream
    }

    /** Extension for a media segment: from its own path when recognisable, else from the session's sniffed format. */
    fun segmentExtension(
        url: String,
        format: SegmentFormat?,
    ): String =
        when (pathExtension(url)) {
            "vtt", "webvtt" -> {
                EXT_VTT
            }

            "ts" -> {
                EXT_TS
            }

            "aac" -> {
                EXT_AAC
            }

            "m4s", "mp4", "m4a", "m4v", "cmfv", "cmfa" -> {
                EXT_FMP4
            }

            else -> {
                when (format) {
                    SegmentFormat.FMP4 -> EXT_FMP4
                    SegmentFormat.AAC -> EXT_AAC
                    SegmentFormat.TS, null -> EXT_TS
                }
            }
        }
}
