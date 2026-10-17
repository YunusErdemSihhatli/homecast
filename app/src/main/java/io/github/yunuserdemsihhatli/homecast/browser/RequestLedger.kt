package io.github.yunuserdemsihhatli.homecast.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Recent WebView requests, used to tie what page script reports to what the WebView really fetched, and for whom.
 *
 * A URL is "confirmed" for a bridge message from origin O only when the WebView requested it as the main frame, or
 * with a Referer whose origin is O. A URL merely requested by some other frame confirms nothing for O.
 *
 * Residual risk: a frame can still report a URL its own origin requested (e.g. an ad iframe reporting its own ad
 * video); such candidates are ranked by kind and recency like any other, and the user picks what to play. Requests
 * sent without a Referer (no-referrer policy) can only be tied by the main frame's origin.
 */
class RequestLedger(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private class Entry(
        var mainFrame: Boolean = false,
        var refererless: Boolean = false,
        val refererOrigins: MutableSet<String> = HashSet(),
    )

    private val entries =
        object : LinkedHashMap<String, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > capacity
        }

    fun record(
        url: String,
        isMainFrame: Boolean,
        referer: String?,
    ) = synchronized(entries) {
        val entry = entries.getOrPut(key(url)) { Entry() }
        if (isMainFrame) entry.mainFrame = true
        val origin = referer?.toHttpUrlOrNull()?.webOrigin()
        if (origin == null) entry.refererless = true else entry.refererOrigins += origin
    }

    /** True when [url] was requested as the main frame or with a Referer from [sourceOrigin]. */
    fun confirms(
        url: String,
        sourceOrigin: String?,
    ): Boolean =
        synchronized(entries) {
            val entry = entries[key(url)] ?: return false
            if (entry.mainFrame) return true
            val origin = sourceOrigin?.toHttpUrlOrNull()?.webOrigin() ?: return false
            origin in entry.refererOrigins
        }

    /**
     * Whether a bridge message from [sourceOrigin] may replace the guessed (top page) headers of [url]: it is
     * [confirms]ed, or the WebView requested [url] without a Referer and the message comes from the top page's own
     * origin (the origin the guess was made from).
     */
    fun mayUpgradeHeaders(
        url: String,
        sourceOrigin: String?,
        topPageUrl: String?,
    ): Boolean {
        if (confirms(url, sourceOrigin)) return true
        val source = sourceOrigin?.toHttpUrlOrNull()?.webOrigin() ?: return false
        val top = topPageUrl?.toHttpUrlOrNull()?.webOrigin() ?: return false
        return source == top && synchronized(entries) { entries[key(url)]?.refererless == true }
    }

    /** A new document: requests made for the previous one no longer confirm anything. */
    fun clear() = synchronized(entries) { entries.clear() }

    private fun key(url: String) = url.substringBefore('#')

    private companion object {
        const val DEFAULT_CAPACITY = 512
        const val INITIAL_CAPACITY = 64
        const val LOAD_FACTOR = 0.75f
    }
}
