package io.github.yunuserdemsihhatli.homecast.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostBlocklistTest {
    @Test
    fun parsesHostsFormatAndPlainLists() {
        val list =
            HostBlocklist.parse(
                """
                # a comment
                0.0.0.0 ads.x.com
                0.0.0.0 0.0.0.0
                0.0.0.0 tracker.z.com # trailing comment
                ads.y.com
                
                """.trimIndent(),
            )

        assertTrue(list.isBlocked("ads.x.com"))
        assertTrue(list.isBlocked("tracker.z.com"))
        assertTrue(list.isBlocked("ads.y.com"))
        assertFalse(list.isBlocked("0.0.0.0"))
        assertFalse(list.isBlocked("x.com"))
        assertEquals(3, list.size)
    }

    @Test
    fun blocksSubdomainsOfListedDomain() {
        val list = HostBlocklist.parse("doubleclick.net")

        assertTrue(list.isBlocked("doubleclick.net"))
        assertTrue(list.isBlocked("stats.g.doubleclick.net"))
        assertFalse(list.isBlocked("notdoubleclick.net"))
        assertFalse(list.isBlocked("net"))
        assertFalse(list.isBlocked(""))
    }

    @Test
    fun caseInsensitive() {
        val list = HostBlocklist.parse("0.0.0.0 Ads.Example.COM")

        assertTrue(list.isBlocked("ads.example.com"))
        assertTrue(list.isBlocked("CDN.ADS.EXAMPLE.COM"))
    }
}
