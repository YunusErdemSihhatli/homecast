package io.github.yunuserdemsihhatli.homecast.capture

/** URL heuristics telling real subtitle files apart from HLS subtitle segments and thumbnail tracks. */
internal object SubtitleUrls {
    private val SEGMENT_WORD = Regex("""(^|[^a-z])(seg|segment|chunk|frag|fragment)s?(\d|[^a-z]|$)""")
    private val NUMBERED = Regex("""\d+""")
    private val THUMBNAIL_WORD = Regex("""thumb|sprite|storyboard|preview""")

    /** The file name (without extension) is a bare number or names a segment, chunk or fragment. */
    fun hasSegmentName(url: String): Boolean {
        val name = fileName(url).substringBeforeLast('.').lowercase()
        return NUMBERED.matches(name) || SEGMENT_WORD.containsMatchIn(name)
    }

    /** Thumbnail sprites and storyboards are WebVTT too, but not subtitles. */
    fun isThumbnailTrack(url: String): Boolean = THUMBNAIL_WORD.containsMatchIn(path(url).lowercase())

    /** `scheme://host/dir/` of [url], or null when it has no path. */
    fun directoryOf(url: String): String? {
        val base = url.substringBefore('#').substringBefore('?')
        val slash = base.lastIndexOf('/')
        return if (slash < base.indexOf("://") + 3) null else base.substring(0, slash + 1)
    }

    private fun path(url: String): String {
        val base = url.substringBefore('#').substringBefore('?')
        val afterScheme = base.substringAfter("://", base)
        return afterScheme.substringAfter('/', "")
    }

    private fun fileName(url: String): String = path(url).substringAfterLast('/')
}
