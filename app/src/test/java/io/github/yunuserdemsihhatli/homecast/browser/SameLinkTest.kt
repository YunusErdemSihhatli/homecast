package io.github.yunuserdemsihhatli.homecast.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameLinkTest {
    @Test fun identicalLinksMatch() {
        assertTrue(isSameLink("https://example.com/watch?ep=2", "https://example.com/watch?ep=2"))
    }

    @Test fun schemeAndHostCaseFragmentAndEmptyPathAreIgnored() {
        assertTrue(isSameLink("https://example.com/", "HTTPS://Example.COM#top"))
        assertTrue(isSameLink("https://example.com/a/../b", "https://example.com/b"))
    }

    @Test fun aDifferentSiteOrPageDoesNotMatch() {
        assertFalse(isSameLink("https://ads.example.net/", "https://example.com/watch"))
        assertFalse(isSameLink("https://example.com/watch?ep=3", "https://example.com/watch?ep=2"))
        assertFalse(isSameLink("http://example.com/watch", "https://example.com/watch"))
    }

    @Test fun missingOrNonWebHrefsNeverMatch() {
        assertFalse(isSameLink("https://example.com/", ""))
        assertFalse(isSameLink("javascript:void(0)", "javascript:void(0)"))
        assertFalse(isSameLink("intent://x#Intent;end", "intent://x#Intent;end"))
        assertFalse(isSameLink("https://example.com/a b", "https://example.com/a b"))
    }
}
