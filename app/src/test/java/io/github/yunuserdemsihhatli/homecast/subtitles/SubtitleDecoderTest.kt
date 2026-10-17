package io.github.yunuserdemsihhatli.homecast.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleDecoderTest {
    private companion object {
        const val BOM_CODE = 0xFEFF
    }

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .use { it.readBytes() }

    @Test
    fun decodesUtf8WithBom() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals("Çağrı şöyle", SubtitleDecoder.decode(bom + "Çağrı şöyle".toByteArray()))
    }

    @Test
    fun decodesPlainUtf8() = assertEquals("ığüşöç", SubtitleDecoder.decode("ığüşöç".toByteArray(Charsets.UTF_8)))

    @Test
    fun fallsBackToWindows1254() =
        assertEquals("ığüşöçİĞ", SubtitleDecoder.decode("ığüşöçİĞ".toByteArray(charset("windows-1254"))))

    @Test
    fun decodesUtf16LeBom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Çağrı".toByteArray(Charsets.UTF_16LE)
        assertEquals("Çağrı", SubtitleDecoder.decode(bytes))
    }

    @Test
    fun decodesUtf16BeBom() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Çağrı".toByteArray(Charsets.UTF_16BE)
        assertEquals("Çağrı", SubtitleDecoder.decode(bytes))
    }

    @Test
    fun decodesCp1254Fixture() {
        val text = SubtitleDecoder.decode(fixture("tr_cp1254.srt"))
        assertTrue(text.contains("Çağrı şöyle İyi ığüşöç"))
        assertTrue(text.contains("Güneş ĞÜŞ"))
    }

    @Test
    fun decodesUtf8BomFixtureWithoutBomCharacter() {
        val text = SubtitleDecoder.decode(fixture("tr_utf8_bom.srt"))
        assertFalse(text.first().code == BOM_CODE)
        assertTrue(text.startsWith("1\r\n"))
        assertTrue(text.contains("Çağrı şöyle İyi ığüşöç"))
    }

    @Test
    fun decodesEmptyInput() = assertEquals("", SubtitleDecoder.decode(ByteArray(0)))
}
