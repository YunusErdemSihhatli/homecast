package io.github.yunuserdemsihhatli.homecast.proxy

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A parsed proxy path; [mac] still has to be checked against the session secret with [ProxyUrls.verify]. */
data class ProxyPath(
    val token: String,
    val upstream: String,
    val mac: String,
    val ext: String,
)

/**
 * Proxy URL scheme: `http://host:port/s/{token}/u/{base64url(upstream)}.{mac}.{ext}`.
 *
 * The token selects a session; the mac (HMAC-SHA256 of extension and upstream under the session secret, 128 bits) proves the
 * upstream was put there by this proxy, so a token holder cannot make the phone fetch arbitrary URLs. The extension
 * lets the Cast receiver infer the content type.
 */
object ProxyUrls {
    private const val RANDOM_BYTES = 16
    private const val MAC_BYTES = 16
    private const val HMAC = "HmacSHA256"
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val pathPattern =
        Regex("^/s/([A-Za-z0-9_-]+)/u/([A-Za-z0-9_-]+)\\.([A-Za-z0-9_-]{22})\\.([a-z0-9]{1,5})$")

    /** A fresh 128-bit, base64url-encoded session token. */
    fun newToken(): String = encoder.encodeToString(newSecret())

    /** A fresh 128-bit session secret for [mac]. */
    fun newSecret(): ByteArray = ByteArray(RANDOM_BYTES).also(random::nextBytes)

    /** Covers the extension too, so a signed URL cannot be redirected to another handler by renaming it. */
    fun mac(
        secret: ByteArray,
        upstream: String,
        ext: String,
    ): String {
        val hmac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(secret, HMAC)) }
        return encoder.encodeToString(hmac.doFinal("$ext\n$upstream".toByteArray(Charsets.UTF_8)).copyOf(MAC_BYTES))
    }

    fun build(
        host: String,
        port: Int,
        token: String,
        secret: ByteArray,
        upstream: String,
        ext: String,
    ): String {
        val encoded = encoder.encodeToString(upstream.toByteArray(Charsets.UTF_8))
        return "http://$host:$port/s/$token/u/$encoded.${mac(secret, upstream, ext)}.$ext"
    }

    fun parse(path: String): ProxyPath? {
        val match = pathPattern.matchEntire(path) ?: return null
        val (token, encoded, mac, ext) = match.destructured
        val upstream =
            try {
                String(decoder.decode(encoded), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return null
            }
        return ProxyPath(token, upstream, mac, ext)
    }

    /** Constant-time check that [path] was built by [build] with [secret]. */
    fun verify(
        secret: ByteArray,
        path: ProxyPath,
    ): Boolean = MessageDigest.isEqual(mac(secret, path.upstream, path.ext).toByteArray(), path.mac.toByteArray())
}
