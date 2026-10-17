package io.github.yunuserdemsihhatli.homecast.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedLinkTest {
    @Test fun extractsFirstWebUrlFromSharedText() {
        assertEquals(
            "https://video.example/watch/1?x=2",
            SharedLink.firstWebUrl("Look at this: https://video.example/watch/1?x=2 and http://other.example/"),
        )
        assertEquals("http://video.example/a", SharedLink.firstWebUrl("(http://video.example/a)."))
        assertEquals("HTTPS://video.example/", SharedLink.firstWebUrl("HTTPS://video.example/"))
    }

    @Test fun ignoresTextWithoutWebUrl() {
        assertNull(SharedLink.firstWebUrl("no link here"))
        assertNull(SharedLink.firstWebUrl("ftp://files.example/a"))
        assertNull(SharedLink.firstWebUrl(null))
    }

    @Test fun acceptsOnlyWebSchemesForViewIntents() {
        assertEquals("https://video.example/a", SharedLink.webUrlOrNull("https://video.example/a"))
        assertNull(SharedLink.webUrlOrNull("intent://evil#Intent;end"))
        assertNull(SharedLink.webUrlOrNull("file:///sdcard/a.html"))
        assertNull(SharedLink.webUrlOrNull(null))
    }
}
