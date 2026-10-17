package io.github.yunuserdemsihhatli.homecast.browser

import android.os.SystemClock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Blocks main-frame navigations the user did not ask for: non-web schemes (intent://, market://...)
 * and cross-site jumps that happen without a recent user gesture (ad redirects).
 *
 * A navigation the user started (address bar, back/forward, shared link: [onUserNavigation]; or a tap, i.e. a
 * non-redirect request with a gesture or within [gestureWindowMillis] of one) opens an allowance: the redirects of
 * that chain may go anywhere. The allowance is tied to the chain (it records the URL the chain has reached, and any
 * main-frame navigation the page starts on its own ends it) and is short-lived: it ends when the navigation commits,
 * finishes or fails, when it turns into a download ([onNavigationEnded]), and at the latest [userNavigationMillis]
 * after it began, so a navigation that never commits cannot leave it open for a later ad redirect. (WebView does not
 * say which navigation a redirect belongs to, so the allowance cannot be tied more tightly than that.)
 *
 * [siteOf] maps a host to its registrable site; injectable because OkHttp's public-suffix list
 * is unavailable in JVM unit tests.
 */
class RedirectGuard(
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val gestureWindowMillis: Long = 1500,
    private val siteOf: (String) -> String = ::defaultSiteOf,
    private val userNavigationMillis: Long = USER_NAVIGATION_MILLIS,
) {
    /** The redirect allowance of a user-started navigation chain. */
    private class Allowance(
        val chainUrl: String?,
        val expiresAt: Long,
    )

    @Volatile private var lastGestureAt: Long? = null

    @Volatile private var allowance: Allowance? = null

    /** A touch on the page: navigations starting shortly after it count as the user's. */
    fun onUserGesture() {
        lastGestureAt = clock()
    }

    /**
     * The app itself navigates for the user (address bar, back/forward, shared link) to [targetUrl] (null when only
     * the history knows it).
     */
    fun onUserNavigation(targetUrl: String? = null) {
        onUserGesture()
        open(targetUrl)
    }

    /** The main frame committed a new document (`onPageStarted`): later redirects belong to another navigation. */
    fun onNavigationCommitted() = onNavigationEnded()

    /** The navigation finished, failed, or turned into a download: its redirects are over. */
    fun onNavigationEnded() {
        allowance = null
    }

    /** The URL the current user navigation chain has reached; null when there is no live allowance. Tests only. */
    internal val allowedChainUrl: String? get() = liveAllowance()?.chainUrl

    fun shouldBlockNavigation(
        currentUrl: String?,
        targetUrl: String,
        isMainFrame: Boolean,
        hasGesture: Boolean,
        isRedirect: Boolean = false,
    ): Boolean {
        if (!isMainFrame) return false
        if (targetUrl.startsWith("about:", ignoreCase = true)) return false
        val target = targetUrl.toHttpUrlOrNull() ?: return true
        val byUser = hasGesture || hasRecentGesture()
        if (isRedirect) {
            if (liveAllowance() != null) {
                open(targetUrl, keepDeadline = true)
                return false
            }
        } else if (byUser) {
            // A new navigation the user started: its redirects get an allowance.
            open(targetUrl)
        } else {
            // The page navigates on its own: whatever the user started before is no longer this chain.
            allowance = null
        }
        if (currentUrl == null) return false
        val current = currentUrl.toHttpUrlOrNull() ?: return false
        if (siteOf(current.host) == siteOf(target.host)) return false
        return !byUser
    }

    private fun open(
        chainUrl: String?,
        keepDeadline: Boolean = false,
    ) {
        val expiresAt = allowance?.takeIf { keepDeadline }?.expiresAt ?: (clock() + userNavigationMillis)
        allowance = Allowance(chainUrl, expiresAt)
    }

    private fun liveAllowance(): Allowance? = allowance?.takeIf { clock() < it.expiresAt }

    private fun hasRecentGesture(): Boolean {
        val at = lastGestureAt ?: return false
        return clock() - at < gestureWindowMillis
    }

    private companion object {
        /** Longest a user navigation's redirects are allowed, should it never commit, finish or fail. */
        const val USER_NAVIGATION_MILLIS = 30_000L

        fun defaultSiteOf(host: String): String =
            try {
                "https://$host/".toHttpUrl().topPrivateDomain() ?: host
            } catch (_: Exception) {
                host
            }
    }
}
