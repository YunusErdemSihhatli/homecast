package io.github.yunuserdemsihhatli.homecast.proxy

import io.github.yunuserdemsihhatli.homecast.capture.UpstreamHeaders
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamRequestsTest {
    private val headers = UpstreamHeaders("https://page.example/", "https://page.example", "UA", "sid=42")
    private val scope = CookieScope.of("https://media.example/v/index.m3u8")!!

    @Test fun cookieScopeMatchesSchemeHostAndPort() {
        assertTrue(scope.allows("https://media.example/v/seg.ts".toHttpUrl()))
        assertTrue(scope.allows("https://media.example:443/other".toHttpUrl()))
        assertFalse("never downgrade to http", scope.allows("http://media.example/v/seg.ts".toHttpUrl()))
        assertFalse(scope.allows("https://media.example:8443/v/seg.ts".toHttpUrl()))
        assertFalse(scope.allows("https://cdn.media.example/v/seg.ts".toHttpUrl()))
    }

    @Test fun cookieOnlyAttachedInsideScope() {
        assertEquals("sid=42", upstreamRequest("https://media.example/a.ts", headers, scope)!!.header("Cookie"))
        val http = upstreamRequest("http://media.example/a.ts", headers, scope)!!
        assertNull(http.header("Cookie"))
        assertEquals("https://page.example/", http.header("Referer"))
        assertEquals("UA", http.header("User-Agent"))
    }
}
