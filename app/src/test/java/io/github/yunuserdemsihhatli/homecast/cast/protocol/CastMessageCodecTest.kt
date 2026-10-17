package io.github.yunuserdemsihhatli.homecast.cast.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CastMessageCodecTest {
    private val ping =
        CastMessage(
            "sender-0",
            "receiver-0",
            "urn:x-cast:com.google.cast.tp.heartbeat",
            "{\"type\":\"PING\"}",
        )

    @Test fun encodesKnownBytes() {
        val expected =
            byteArrayOf(0x08, 0x00, 0x12, 0x08) + "sender-0".toByteArray() +
                byteArrayOf(0x1A, 0x0A) + "receiver-0".toByteArray() +
                byteArrayOf(0x22, 0x27) + "urn:x-cast:com.google.cast.tp.heartbeat".toByteArray() +
                byteArrayOf(0x28, 0x00, 0x32, 0x0F) + "{\"type\":\"PING\"}".toByteArray()
        assertArrayEquals(expected, CastMessageCodec.encode(ping))
    }

    @Test fun roundTripsUnicodePayload() {
        val m = ping.copy(payloadUtf8 = "{\"title\":\"Çay saati ☕ 日本語\"}" + "x".repeat(300))
        assertEquals(m, CastMessageCodec.decode(CastMessageCodec.encode(m)))
    }

    @Test fun decodeSkipsUnknownFieldsAndBinaryPayload() {
        val bytes =
            CastMessageCodec.encode(ping) +
                byteArrayOf(0x3A, 0x03, 1, 2, 3) + // field 7 payload_binary
                byteArrayOf(0x40, 0x96.toByte(), 0x01) + // field 8 varint
                byteArrayOf(0x4D, 1, 2, 3, 4) + // field 9 fixed32
                byteArrayOf(0x52, 0x01, 0x7F) // field 10 length-delimited
        assertEquals(ping, CastMessageCodec.decode(bytes))
    }

    @Test(expected = java.io.IOException::class)
    fun decodeRejectsTruncatedField() {
        CastMessageCodec.decode(byteArrayOf(0x12, 0x08, 0x61))
    }
}
