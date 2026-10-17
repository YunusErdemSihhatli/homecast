package io.github.yunuserdemsihhatli.homecast.capture

sealed interface Classification {
    data class Video(
        val kind: MediaKind,
    ) : Classification

    data class Subtitle(
        val format: SubtitleFormat,
    ) : Classification
}

/** Decides from a URL or a Content-Type whether a network response is a castable video or a subtitle. */
object MediaClassifier {
    private val byExtension =
        mapOf(
            "m3u8" to Classification.Video(MediaKind.HLS),
            "mp4" to Classification.Video(MediaKind.FILE),
            "webm" to Classification.Video(MediaKind.FILE),
            "mkv" to Classification.Video(MediaKind.FILE),
            "mpd" to Classification.Video(MediaKind.DASH),
            "vtt" to Classification.Subtitle(SubtitleFormat.VTT),
            "srt" to Classification.Subtitle(SubtitleFormat.SRT),
        )

    private val byContentType =
        mapOf(
            "application/vnd.apple.mpegurl" to Classification.Video(MediaKind.HLS),
            "application/x-mpegurl" to Classification.Video(MediaKind.HLS),
            "audio/mpegurl" to Classification.Video(MediaKind.HLS),
            "audio/x-mpegurl" to Classification.Video(MediaKind.HLS),
            "video/mp4" to Classification.Video(MediaKind.FILE),
            "video/webm" to Classification.Video(MediaKind.FILE),
            "video/x-matroska" to Classification.Video(MediaKind.FILE),
            "application/dash+xml" to Classification.Video(MediaKind.DASH),
            "text/vtt" to Classification.Subtitle(SubtitleFormat.VTT),
            "application/x-subrip" to Classification.Subtitle(SubtitleFormat.SRT),
        )

    /** Returns null for anything that is not a video or subtitle (segments, images, scripts...). */
    fun classifyUrl(url: String): Classification? {
        val path = url.substringBefore('#').substringBefore('?')
        val lastSegment = path.substringAfterLast('/')
        if (!lastSegment.contains('.')) return null
        return byExtension[lastSegment.substringAfterLast('.').lowercase()]
    }

    fun classifyContentType(contentType: String?): Classification? {
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return byContentType[mime]
    }
}
