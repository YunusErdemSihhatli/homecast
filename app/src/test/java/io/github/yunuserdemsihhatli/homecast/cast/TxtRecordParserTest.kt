package io.github.yunuserdemsihhatli.homecast.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TxtRecordParserTest {
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun parsesFriendlyNameModelAndId() {
        val device =
            TxtRecordParser.toDevice(
                serviceName = "Chromecast-abc",
                host = "192.168.1.20",
                port = 8009,
                attributes = mapOf("fn" to bytes("Oturma Odası"), "md" to bytes("Chromecast"), "id" to bytes("abc123")),
            )
        assertEquals(CastDevice("abc123", "Oturma Odası", "192.168.1.20", 8009, "Chromecast"), device)
    }

    @Test
    fun fallsBackToServiceNameWhenFnMissing() {
        val device = TxtRecordParser.toDevice("Chromecast-abc", "10.0.0.5", 8009, mapOf("fn" to null))
        assertEquals("Chromecast-abc", device.name)
        assertNull(device.id)
        assertNull(device.model)
    }
}
