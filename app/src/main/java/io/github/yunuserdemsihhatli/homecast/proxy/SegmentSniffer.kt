package io.github.yunuserdemsihhatli.homecast.proxy

enum class SegmentFormat(
    val castValue: String,
) {
    TS("ts"),
    FMP4("fmp4"),
    AAC("aac"),
}

/** Recognises HLS segment payloads by magic bytes, including TS disguised behind a fake image header. */
object SegmentSniffer {
    private const val TS_SYNC = 0x47
    private const val TS_PACKET = 188

    /**
     * Signatures at offset 0 win. Only behind an image magic (a segment disguised as a picture) is the head scanned
     * for a later TS start: fMP4/AAC payloads contain 0x47 bytes 188 apart by chance often enough to matter.
     */
    fun detect(head: ByteArray): SegmentFormat? =
        when {
            isTsAt(head, 0) -> SegmentFormat.TS
            isFmp4(head) -> SegmentFormat.FMP4
            isId3(head) || isAdts(head) -> SegmentFormat.AAC
            hasImageMagic(head) && tsStartOffset(head) >= 0 -> SegmentFormat.TS
            else -> null
        }

    /** Offset of the first byte of a run of three TS packets, or -1 when none is found. */
    fun tsStartOffset(head: ByteArray): Int {
        var o = 0
        while (o + 2 * TS_PACKET < head.size) {
            if (isTsAt(head, o)) return o
            o++
        }
        return -1
    }

    private fun isTsAt(
        head: ByteArray,
        o: Int,
    ): Boolean =
        o + 2 * TS_PACKET < head.size &&
            head[o].toInt() == TS_SYNC &&
            head[o + TS_PACKET].toInt() == TS_SYNC &&
            head[o + 2 * TS_PACKET].toInt() == TS_SYNC

    private val imageMagics =
        listOf(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), // PNG
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), // JPEG
            "GIF8".toByteArray(Charsets.ISO_8859_1), // GIF
        )

    private fun hasImageMagic(head: ByteArray): Boolean =
        imageMagics.any { magic -> head.size >= magic.size && magic.indices.all { head[it] == magic[it] } }

    private fun isFmp4(head: ByteArray): Boolean {
        if (head.size < 8) return false
        val box = String(head, 4, 4, Charsets.ISO_8859_1)
        return box == "ftyp" || box == "styp" || box == "moof"
    }

    /** Packed audio (AAC/MP3 elementary stream) starts with an ID3 timestamp tag. */
    private fun isId3(head: ByteArray): Boolean = head.size >= 3 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3"

    private fun isAdts(head: ByteArray): Boolean {
        if (head.size < 2) return false
        val b0 = head[0].toInt() and 0xFF
        val b1 = head[1].toInt() and 0xFF
        return b0 == 0xFF && (b1 and 0xF0) == 0xF0
    }
}
