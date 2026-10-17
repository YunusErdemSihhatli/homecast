package io.github.yunuserdemsihhatli.homecast.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyUrlsTest {
    private val upstream = "https://cdn.example/path/seg 1.ts?token=a+b/c&x=1"
    private val secret = ProxyUrls.newSecret()

    @Test fun buildsUrlWithTokenEncodedUpstreamMacAndExtension() {
        val url = ProxyUrls.build("192.168.1.20", 41234, "tok", secret, upstream, "ts")
        assertTrue(url, url.startsWith("http://192.168.1.20:41234/s/tok/u/"))
        assertTrue(url, url.endsWith(".ts"))
        val (encoded, mac) = url.removePrefix("http://192.168.1.20:41234/s/tok/u/").removeSuffix(".ts").split('.')
        assertTrue(encoded, encoded.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals(ProxyUrls.mac(secret, upstream, "ts"), mac)
        assertEquals(22, mac.length) // 128-bit truncated HMAC-SHA256
    }

    @Test fun parseRoundTripsAndVerifies() {
        val url = ProxyUrls.build("127.0.0.1", 8080, "abc_-DEF", secret, upstream, "m3u8")
        val path = ProxyUrls.parse(url.removePrefix("http://127.0.0.1:8080"))!!
        assertEquals("abc_-DEF", path.token)
        assertEquals(upstream, path.upstream)
        assertEquals("m3u8", path.ext)
        assertTrue(ProxyUrls.verify(secret, path))
        assertFalse(ProxyUrls.verify(ProxyUrls.newSecret(), path))
        assertFalse(ProxyUrls.verify(secret, path.copy(upstream = "https://evil.example/")))
        assertFalse("MAC must cover the extension", ProxyUrls.verify(secret, path.copy(ext = "vtt")))
    }

    @Test fun parseRejectsMalformedPaths() {
        val mac = ProxyUrls.mac(secret, "hello", "ts")
        assertNull(ProxyUrls.parse("/"))
        assertNull(ProxyUrls.parse("/s/tok"))
        assertNull(ProxyUrls.parse("/s/tok/u/"))
        assertNull(ProxyUrls.parse("/s/tok/u/aGVsbG8.ts")) // no MAC
        assertNull(ProxyUrls.parse("/x/tok/u/aGVsbG8.$mac.ts"))
        assertNull(ProxyUrls.parse("/s/tok/u/%%%.$mac.ts"))
        assertNull(ProxyUrls.parse("/s/tok/u/aGVsbG8.$mac.ts/extra"))
        assertEquals("hello", ProxyUrls.parse("/s/tok/u/aGVsbG8.$mac.ts")?.upstream)
    }

    @Test fun tokensAndSecretsAreUnguessable() {
        val tokens = (1..200).map { ProxyUrls.newToken() }.toSet()
        assertEquals(200, tokens.size)
        tokens.forEach {
            assertEquals(22, it.length) // 128 bits, base64url without padding
            assertTrue(it, it.matches(Regex("[A-Za-z0-9_-]+")))
        }
        assertNotEquals(ProxyUrls.newToken(), ProxyUrls.newToken())
        assertEquals(16, ProxyUrls.newSecret().size)
        assertFalse(ProxyUrls.newSecret().contentEquals(ProxyUrls.newSecret()))
    }
}
