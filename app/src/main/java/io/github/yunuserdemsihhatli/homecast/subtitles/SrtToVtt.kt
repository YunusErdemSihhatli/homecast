package io.github.yunuserdemsihhatli.homecast.subtitles

/** Converts SubRip (SRT) subtitles to WebVTT, which is what the Cast receiver can display. */
object SrtToVtt {
    private const val HEADER = "WEBVTT"
    private val BOM = Char(0xFEFF)
    private const val ARROW = "-->"
    private val blankLines = Regex("\n[ \t]*\n(?:[ \t]*\n)*")
    private val timestamp = Regex("(?<!\\d)(\\d{1,2}):(\\d{2}):(\\d{2}),(\\d{3})")
    private val fontTag = Regex("</?font\\b[^>]*>", RegexOption.IGNORE_CASE)

    /** Returns [text] unchanged if it is already WebVTT, otherwise converts it from SRT. */
    fun ensureVtt(text: String): String = if (text.trimStart(BOM).startsWith(HEADER)) text else convert(text)

    fun convert(srt: String): String {
        val normalized = srt.trimStart(BOM).replace("\r\n", "\n").replace('\r', '\n')
        val cues =
            normalized
                .split(blankLines)
                .mapNotNull(::convertCue)
        return buildString {
            append(HEADER).append("\n\n")
            append(cues.joinToString("\n\n"))
            if (cues.isNotEmpty()) append('\n')
        }
    }

    private fun convertCue(block: String): String? {
        val lines = block.trim('\n').split('\n')
        val timingIndex = lines.indexOfFirst { ARROW in it }
        if (timingIndex < 0) return null // no timing line: stray index or garbage
        val text = lines.drop(timingIndex + 1).map { it.replace(fontTag, "") }
        if (text.all { it.isBlank() }) return null
        val timing =
            timestamp.replace(lines[timingIndex].trim()) {
                val (h, m, s, ms) = it.destructured
                "${h.padStart(2, '0')}:$m:$s.$ms"
            }
        return (listOf(timing) + text).joinToString("\n")
    }
}
