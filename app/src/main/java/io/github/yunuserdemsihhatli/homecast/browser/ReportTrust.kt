package io.github.yunuserdemsihhatli.homecast.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Whether a `capture.js` report about a subtitle playlist or a `<track>` may change what was captured by others
 * (evict candidates, replace a subtitle entry, take priority in the subtitle cap). Any frame, including an ad iframe,
 * can post anything to the bridge, so only two reports qualify: one tied to a request the WebView saw for the
 * sending frame ([confirmedByLedger], see [RequestLedger.confirms]), or one sent by the main page's own origin.
 */
internal object ReportTrust {
    fun isTrusted(
        confirmedByLedger: Boolean,
        sourceOrigin: String?,
        pageUrl: String?,
    ): Boolean {
        if (confirmedByLedger) return true
        val source = sourceOrigin?.toHttpUrlOrNull()?.webOrigin() ?: return false
        val page = pageUrl?.toHttpUrlOrNull()?.webOrigin() ?: return false
        return source == page
    }
}
