package io.github.yunuserdemsihhatli.homecast.browser

import android.content.Context

/**
 * Set of ad/tracker hosts. A host is blocked when it, or any parent domain of it, is listed.
 */
class HostBlocklist(
    hosts: Set<String>,
) {
    private val hosts: HashSet<String> = hosts.mapTo(HashSet(hosts.size * 2)) { it.lowercase() }

    val size: Int get() = hosts.size

    /** Fast path for every WebView request: one hash lookup per label, no regex or allocation beyond substrings. */
    fun isBlocked(host: String): Boolean {
        var candidate = host.lowercase().trimEnd('.')
        while (candidate.isNotEmpty()) {
            if (candidate in hosts) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
        return false
    }

    companion object {
        private const val ASSET_NAME = "blocklist.txt"
        private val IGNORED = setOf("0.0.0.0", "localhost", "localhost.localdomain", "local", "broadcasthost")

        /** Accepts hosts-file lines ("0.0.0.0 host") and plain one-host-per-line lists; `#` starts a comment. */
        fun parse(text: String): HostBlocklist {
            val hosts = HashSet<String>()
            text.lineSequence().forEach { raw ->
                val tokens = raw.substringBefore('#').trim().split(WHITESPACE)
                val host =
                    when (tokens.size) {
                        1 -> tokens[0]
                        2 -> tokens[1]
                        else -> ""
                    }.lowercase()
                if (host.isNotEmpty() && host !in IGNORED) hosts += host
            }
            return HostBlocklist(hosts)
        }

        private val WHITESPACE = Regex("\\s+")

        /** Blocking read of the bundled asset (tens of thousands of lines): call off the main thread. */
        fun load(context: Context): HostBlocklist =
            context.assets
                .open(ASSET_NAME)
                .bufferedReader()
                .use { parse(it.readText()) }
    }
}
