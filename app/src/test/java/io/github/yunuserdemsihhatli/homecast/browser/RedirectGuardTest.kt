package io.github.yunuserdemsihhatli.homecast.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectGuardTest {
    private var now = 10_000L

    private fun guard() = RedirectGuard(clock = { now }, siteOf = ::fakeSiteOf)

    @Test
    fun blocksNonHttpSchemesAlways() {
        val guard = guard()
        guard.onUserGesture()

        assertTrue(guard.shouldBlockNavigation("https://a.example/", "intent://open#Intent;end", true, true))
        assertTrue(guard.shouldBlockNavigation("https://a.example/", "market://details?id=x", true, true))
    }

    @Test
    fun blocksCrossSiteMainFrameWithoutGesture() {
        assertTrue(guard().shouldBlockNavigation("https://a.example/", "https://b.other/", true, false))
    }

    @Test
    fun allowsCrossSiteWithRecentGesture() {
        val guard = guard()
        guard.onUserGesture()

        now += 1_000
        assertFalse(guard.shouldBlockNavigation("https://a.example/", "https://b.other/", true, false))
        now += 1_000
        assertTrue(guard.shouldBlockNavigation("https://a.example/", "https://b.other/", true, false))
    }

    @Test
    fun allowsCrossSiteWhenRequestCarriesGesture() {
        assertFalse(guard().shouldBlockNavigation("https://a.example/", "https://b.other/", true, true))
    }

    @Test
    fun allowsSameSiteRedirects() {
        val guard = guard()

        assertFalse(guard.shouldBlockNavigation("https://www.a.example/", "https://cdn.a.example/x", true, false))
        assertFalse(guard.shouldBlockNavigation("https://www.a.com.tr/", "https://cdn.a.com.tr/x", true, false))
        assertTrue(guard.shouldBlockNavigation("https://www.a.com.tr/", "https://www.b.com.tr/x", true, false))
    }

    @Test
    fun neverBlocksSubframes() {
        assertFalse(guard().shouldBlockNavigation("https://a.example/", "https://b.other/", false, false))
    }

    @Test
    fun allowsFirstLoad() {
        assertFalse(guard().shouldBlockNavigation(null, "https://b.other/", true, false))
    }

    @Test
    fun allowsAboutBlankAndSameDocumentSchemes() {
        assertFalse(guard().shouldBlockNavigation("https://a.example/", "about:blank", true, false))
    }

    @Test
    fun userNavigationRedirectsPassUntilCommitHoweverLate() {
        val guard = guard()
        guard.onUserNavigation() // address bar, back/forward, shared link

        now += 10_000 // a slow server answers with a cross-site 302 long after the gesture window
        assertFalse(
            guard.shouldBlockNavigation("https://a.example/", "https://b.other/", true, false, isRedirect = true),
        )
        now += 5_000
        assertFalse(guard.shouldBlockNavigation("https://a.example/", "https://c.cdn/", true, false, isRedirect = true))

        guard.onNavigationCommitted()
        now += 10_000
        assertTrue(guard.shouldBlockNavigation("https://c.cdn/", "https://ads.other/", true, false, isRedirect = true))
    }

    @Test
    fun linkTapRedirectsPassUntilCommit() {
        val guard = guard()
        // The tap itself carries the gesture; its server redirects arrive later and without one.
        assertFalse(guard.shouldBlockNavigation("https://a.example/", "https://a.example/go", true, true))
        now += 8_000
        assertFalse(
            guard.shouldBlockNavigation("https://a.example/", "https://b.other/film", true, false, isRedirect = true),
        )
    }

    @Test
    fun pageInitiatedNavigationDoesNotInheritAnEarlierUserNavigation() {
        val guard = guard()
        guard.onUserNavigation()
        now += 10_000
        // The page itself starts a same-site navigation (no gesture), which then redirects cross-site: an ad hop.
        assertFalse(guard.shouldBlockNavigation("https://a.example/", "https://a.example/out", true, false))
        assertTrue(
            guard.shouldBlockNavigation("https://a.example/", "https://ads.other/", true, false, isRedirect = true),
        )
    }

    @Test
    fun redirectsWithoutAUserNavigationAreStillBlocked() {
        val guard = guard()
        now += 10_000
        assertTrue(
            guard.shouldBlockNavigation("https://a.example/", "https://b.other/", true, false, isRedirect = true),
        )
    }

    @Test
    fun userNavigationNeverAllowsNonWebSchemes() {
        val guard = guard()
        guard.onUserNavigation()
        assertTrue(guard.shouldBlockNavigation("https://a.example/", "intent://x#Intent;end", true, false, true))
    }

    @Test
    fun userNavigationAllowanceExpiresAfterThirtySeconds() {
        val guard = guard()
        guard.onUserNavigation("https://a.example/film")
        now += 29_000
        assertFalse(guard.shouldBlockNavigation("https://a.example/", "https://b.other/", true, false, true))
        now += 1_000 // the deadline counts from the user's navigation, not from the last redirect
        assertTrue(guard.shouldBlockNavigation("https://a.example/", "https://c.other/", true, false, true))
    }

    @Test
    fun allowanceEndsWhenTheNavigationFinishesFailsOrBecomesADownload() {
        val guard = guard()
        for (end in listOf<(RedirectGuard) -> Unit>({ it.onNavigationEnded() }, { it.onNavigationCommitted() })) {
            guard.onUserNavigation("https://a.example/file.zip")
            end(guard)
            now += 5_000
            assertTrue(guard.shouldBlockNavigation("https://a.example/", "https://ads.other/", true, false, true))
        }
    }

    @Test
    fun staleUserNavigationDoesNotLetALaterPageRedirectThrough() {
        val guard = guard()
        // The user's navigation became a download and never committed (onNavigationEnded is the normal path; the
        // deadline is the backstop when even that never comes).
        guard.onUserNavigation("https://a.example/file.zip")
        now += 31_000
        assertTrue(guard.shouldBlockNavigation("https://a.example/", "https://ads.other/", true, false, true))
    }

    @Test
    fun allowanceFollowsTheChainAndAPageNavigationEndsIt() {
        val guard = guard()
        guard.onUserNavigation("https://a.example/go")
        assertFalse(guard.shouldBlockNavigation("https://a.example/", "https://b.other/x", true, false, true))
        assertEquals("https://b.other/x", guard.allowedChainUrl)

        now += 2_000 // past the gesture window: this one is the page's own
        assertFalse(guard.shouldBlockNavigation("https://a.example/", "https://a.example/next", true, false))
        assertNull(guard.allowedChainUrl)
    }

    private fun fakeSiteOf(host: String): String {
        val labels = host.split('.')
        val take = if (host.endsWith(".com.tr") || host.endsWith(".co.uk")) 3 else 2
        return labels.takeLast(take).joinToString(".")
    }
}
