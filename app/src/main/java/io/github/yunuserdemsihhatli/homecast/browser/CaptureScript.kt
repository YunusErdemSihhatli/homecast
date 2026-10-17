package io.github.yunuserdemsihhatli.homecast.browser

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The `capture.js` page hook: reports HLS playlists fetched by page scripts, `<video>` sources and `<track>`
 * subtitles that network interception alone cannot recognise (e.g. playlists without an `.m3u8` extension).
 */
class CaptureScript(
    val source: String,
) {
    /**
     * Installs the hook into [webView], delivering raw messages to [client]. Preferred path: a document-start script
     * in every frame (iframes included) plus a web message listener. When the WebView lacks document-start scripts,
     * [client] instead injects [source] with evaluateJavascript from onPageStarted — main frame only, and requests the
     * page makes before the injection lands are missed. Without the web message listener the bridge falls back to a
     * JavaScript interface with the same `HomeCast.postMessage(string)` shape; it cannot tell which frame sent a
     * message, so the claimed frame URL is not verified there.
     */
    @SuppressLint("JavascriptInterface")
    fun install(
        webView: WebView,
        client: BrowserWebViewClient,
    ) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                BRIDGE_NAME,
                ALL_ORIGINS,
            ) { _, message, sourceOrigin, _, reply ->
                // Every frame says hello first: its reply proxy is how the app asks that frame to pause its media.
                client.onFrameBridge(reply::postMessage)
                message.data?.let { client.onCaptureMessage(it, sourceOrigin.toString()) }
            }
        } else {
            webView.addJavascriptInterface(JsBridge(client), BRIDGE_NAME)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, source, ALL_ORIGINS)
        } else {
            client.pageStartScript = source
        }
    }

    private class JsBridge(
        private val client: BrowserWebViewClient,
    ) {
        @JavascriptInterface
        fun postMessage(message: String) = client.onCaptureMessage(message, sourceOrigin = null)
    }

    companion object {
        const val BRIDGE_NAME = "HomeCast"

        /** Sent to every frame's `capture.js` to pause the page's own media. */
        const val PAUSE_MESSAGE = "homecast:pause"

        /** The same for the main frame through evaluateJavascript, which also works without the message bridge. */
        const val PAUSE_SCRIPT =
            "(function(){var m=document.querySelectorAll('video,audio');" +
                "for(var i=0;i<m.length;i++){try{m[i].pause();}catch(e){}}})();"
        private const val ASSET_NAME = "capture.js"
        private val ALL_ORIGINS = setOf("*")

        /** Reads the small bundled script. */
        fun load(context: Context): CaptureScript =
            CaptureScript(
                context.assets
                    .open(ASSET_NAME)
                    .bufferedReader()
                    .use { it.readText() },
            )
    }
}

/** A validated message from `capture.js`. Pages can post anything to the bridge, so every field is untrusted. */
sealed interface CaptureMessage {
    val url: String

    /** URL of the frame that saw the media, used as its Referer; null when unknown or not http(s). */
    val frame: String?

    data class Hls(
        override val url: String,
        override val frame: String?,
        val master: Boolean,
    ) : CaptureMessage

    data class Video(
        override val url: String,
        override val frame: String?,
    ) : CaptureMessage

    /** An HLS subtitle rendition playlist: neither a video nor a subtitle file; its directory holds segments. */
    data class SubtitlePlaylist(
        override val url: String,
        override val frame: String?,
    ) : CaptureMessage

    data class Track(
        override val url: String,
        override val frame: String?,
        val label: String,
        val language: String?,
        val showing: Boolean = false,
    ) : CaptureMessage

    companion object {
        const val MAX_MESSAGE_CHARS = 4096
        const val MAX_LABEL_CHARS = 100
        const val MAX_LANGUAGE_CHARS = 35
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Null for anything malformed, oversized, of an unknown type or without an http(s) media URL.
         *
         * [sourceOrigin] is the origin the WebView says the message came from (null when unknown: the JavaScript
         * interface fallback). A message whose `frame` claims a different origin is rejected; a missing frame is
         * replaced by the source origin; a frame from an opaque source origin ("null") cannot be verified and is
         * dropped.
         */
        fun parse(
            message: String,
            sourceOrigin: String?,
        ): CaptureMessage? {
            if (message.length > MAX_MESSAGE_CHARS) return null
            val raw =
                try {
                    json.decodeFromString(RawCaptureMessage.serializer(), message)
                } catch (_: IllegalArgumentException) {
                    return null // SerializationException is an IllegalArgumentException
                }
            val url = webUrl(raw.url) ?: return null
            val claimedFrame = webUrl(raw.frame)
            val frame =
                if (sourceOrigin == null) {
                    claimedFrame
                } else {
                    val source = sourceOrigin.toHttpUrlOrNull()?.webOrigin()
                    when {
                        source == null -> null
                        claimedFrame == null -> "$source/"
                        claimedFrame.toHttpUrlOrNull()?.webOrigin() == source -> claimedFrame
                        else -> return null
                    }
                }
            return when (raw.type) {
                "hls" -> {
                    Hls(url, frame, raw.master)
                }

                "video" -> {
                    Video(url, frame)
                }

                "subplaylist" -> {
                    SubtitlePlaylist(url, frame)
                }

                "track" -> {
                    Track(
                        url = url,
                        frame = frame,
                        label =
                            raw.label
                                .orEmpty()
                                .trim()
                                .take(MAX_LABEL_CHARS),
                        language =
                            raw.lang
                                ?.trim()
                                ?.take(MAX_LANGUAGE_CHARS)
                                ?.ifEmpty { null },
                        showing = raw.showing,
                    )
                }

                else -> {
                    null
                }
            }
        }

        private fun webUrl(value: String?): String? = value?.takeIf { it.toHttpUrlOrNull() != null }
    }
}

/** The wire shape posted by `capture.js`. */
@Serializable
private data class RawCaptureMessage(
    val type: String? = null,
    val url: String? = null,
    val frame: String? = null,
    val master: Boolean = false,
    val label: String? = null,
    val lang: String? = null,
    val showing: Boolean = false,
)

/** `scheme://host[:port]`, the port only when it is not the scheme's default. */
internal fun HttpUrl.webOrigin(): String =
    if (port ==
        HttpUrl.defaultPort(scheme)
    ) {
        "$scheme://$host"
    } else {
        "$scheme://$host:$port"
    }
