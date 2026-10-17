package io.github.yunuserdemsihhatli.homecast.proxy

import io.github.yunuserdemsihhatli.homecast.capture.UpstreamHeaders
import io.github.yunuserdemsihhatli.homecast.hls.HlsRewriter
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException

/** Bytes buffered (never consumed) to recognise a segment, including ones hidden behind a fake image header. */
internal const val SNIFF_BYTES = 64 * 1024
private const val MAX_TEXT_BYTES = 8L * 1024 * 1024

/**
 * Where a captured Cookie header may be sent: only the exact origin (scheme, host and port) of the URL it was
 * captured for. The WebView hands out cookies per URL, so other hosts never had them, and an https cookie must never
 * travel over plain http. Referer, Origin and User-Agent are not secrets and go to every host. Registrable-domain
 * matching via `HttpUrl.topPrivateDomain()` is deliberately not used: on OkHttp's Android artifact it needs the
 * public-suffix asset loaded through androidx.startup and throws in JVM unit tests.
 */
internal class CookieScope(
    url: HttpUrl,
) {
    private val scheme = url.scheme
    private val host = url.host
    private val port = url.port

    fun allows(url: HttpUrl): Boolean = url.scheme == scheme && url.host == host && url.port == port

    companion object {
        fun of(url: String): CookieScope? = url.toHttpUrlOrNull()?.let(::CookieScope)
    }
}

/** Removes the Cookie header on redirect hops that leave the request's [CookieScope]. */
private object CookieScopeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val scope = request.tag(CookieScope::class.java)
        val leaves = scope != null && request.header("Cookie") != null && !scope.allows(request.url)
        return chain.proceed(if (leaves) request.newBuilder().removeHeader("Cookie").build() else request)
    }
}

/** A client sharing [this] one's pool and dispatcher that enforces [CookieScope] on every network hop. */
internal fun OkHttpClient.withCookieScoping(): OkHttpClient =
    newBuilder().addNetworkInterceptor(CookieScopeInterceptor).build()

/** Builds an upstream GET carrying the page's headers, or returns null when [url] is not http(s). */
internal fun upstreamRequest(
    url: String,
    headers: UpstreamHeaders,
    cookieScope: CookieScope?,
    range: String? = null,
    identity: Boolean = false,
): Request? {
    val httpUrl = url.toHttpUrlOrNull() ?: return null
    return Request
        .Builder()
        .url(httpUrl)
        .header("User-Agent", headers.userAgent)
        .apply {
            headers.referer?.let { header("Referer", it) }
            headers.origin?.let { header("Origin", it) }
            if (cookieScope != null && cookieScope.allows(httpUrl)) {
                headers.cookie?.let { header("Cookie", it) }
                tag(CookieScope::class.java, cookieScope)
            }
            range?.let { header("Range", it) }
            // Keep Content-Length/Content-Range byte-exact for media; OkHttp would otherwise gunzip transparently.
            if (identity) header("Accept-Encoding", "identity")
        }.build()
}

/** Buffers up to [SNIFF_BYTES] without consuming them and returns a copy. */
internal fun peekHead(source: BufferedSource): ByteArray {
    source.request(SNIFF_BYTES.toLong())
    return source.buffer.snapshot(minOf(source.buffer.size, SNIFF_BYTES.toLong()).toInt()).toByteArray()
}

/** Reads a playlist body, or returns null when it is not an HLS playlist (checked before reading it all). */
internal fun readPlaylist(source: BufferedSource): String? {
    if (!HlsRewriter.isPlaylist(String(peekHead(source), Charsets.UTF_8))) return null
    return String(readText(source), Charsets.UTF_8)
}

/** Reads a whole text body (playlist, subtitles), refusing anything implausibly large. */
internal fun readText(source: BufferedSource): ByteArray {
    if (source.request(MAX_TEXT_BYTES + 1)) throw IOException("Upstream body larger than $MAX_TEXT_BYTES bytes")
    return source.readByteArray()
}
