package io.github.yunuserdemsihhatli.homecast.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentSnifferTest {
    private fun ts(packets: Int = 4) =
        ByteArray(188 * packets).also { b -> for (i in 0 until packets) b[i * 188] = 0x47 }

    @Test fun detectsTs() {
        assertEquals(SegmentFormat.TS, SegmentSniffer.detect(ts()))
        assertEquals(0, SegmentSniffer.tsStartOffset(ts()))
    }

    @Test fun detectsFmp4() {
        for (box in listOf("ftyp", "styp", "moof")) {
            val head = ByteArray(16).also { box.toByteArray().copyInto(it, 4) }
            assertEquals(box, SegmentFormat.FMP4, SegmentSniffer.detect(head))
        }
    }

    @Test fun findsTsSyncAfterPngPrefix() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val head = png + ByteArray(300) + ts()
        assertEquals(308, SegmentSniffer.tsStartOffset(head))
        assertEquals(SegmentFormat.TS, SegmentSniffer.detect(head))
    }

    @Test fun detectsAdtsAac() {
        assertEquals(
            SegmentFormat.AAC,
            SegmentSniffer.detect(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte())),
        )
        assertEquals(
            SegmentFormat.AAC,
            SegmentSniffer.detect(byteArrayOf(0xFF.toByte(), 0xF9.toByte(), 0x50, 0x80.toByte())),
        )
    }

    @Test fun detectsAllAdtsSyncVariants() {
        for (b1 in listOf(0xF0, 0xF1, 0xF8, 0xF9, 0xFF)) {
            val head = byteArrayOf(0xFF.toByte(), b1.toByte(), 0x50, 0x80.toByte())
            assertEquals(b1.toString(16), SegmentFormat.AAC, SegmentSniffer.detect(head))
        }
        assertNull(SegmentSniffer.detect(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x50, 0x80.toByte())))
    }

    @Test fun detectsId3PrefixedPackedAudio() {
        val head = "ID3".toByteArray() + byteArrayOf(4, 0, 0, 0, 0, 0, 0x3F) + ByteArray(100)
        assertEquals(SegmentFormat.AAC, SegmentSniffer.detect(head))
        assertNull(SegmentSniffer.detect("ID".toByteArray()))
    }

    /** Plants 0x47 bytes 188 apart, as can happen by chance inside an mdat or AAC payload. */
    private fun plantSyncTriple(
        head: ByteArray,
        at: Int,
    ) = head.also { for (k in 0..2) it[at + k * 188] = 0x47 }

    @Test fun fmp4WithChanceSyncBytesStaysFmp4() {
        for (box in listOf("ftyp", "moof")) {
            val head = plantSyncTriple(ByteArray(4096).also { box.toByteArray().copyInto(it, 4) }, 1000)
            assertEquals(box, SegmentFormat.FMP4, SegmentSniffer.detect(head))
        }
    }

    @Test fun aacWithChanceSyncBytesStaysAac() {
        val adts =
            plantSyncTriple(
                ByteArray(4096).also {
                    it[0] = 0xFF.toByte()
                    it[1] = 0xF1.toByte()
                },
                700,
            )
        assertEquals(SegmentFormat.AAC, SegmentSniffer.detect(adts))
        val id3 = plantSyncTriple(ByteArray(4096).also { "ID3".toByteArray().copyInto(it) }, 700)
        assertEquals(SegmentFormat.AAC, SegmentSniffer.detect(id3))
    }

    @Test fun scansPastPrefixOnlyBehindImageMagic() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val gif = "GIF89a".toByteArray()
        for (magic in listOf(jpeg, gif)) {
            val head = magic + ByteArray(500) + ts()
            assertEquals(SegmentFormat.TS, SegmentSniffer.detect(head))
        }
        // Unknown bytes at offset 0: a sync triple further in is not trusted.
        assertNull(SegmentSniffer.detect(ByteArray(500) { 1 } + ts()))
    }

    @Test fun unknownAndShortInputs() {
        assertNull(SegmentSniffer.detect(ByteArray(0)))
        assertNull(SegmentSniffer.detect(ByteArray(50)))
        assertEquals(-1, SegmentSniffer.tsStartOffset(ByteArray(300)))
        assertEquals(-1, SegmentSniffer.tsStartOffset(ByteArray(0)))
    }
}
