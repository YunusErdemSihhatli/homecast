package io.github.yunuserdemsihhatli.homecast.browser

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URI

/**
 * Never opens a second window. With multiple-window support on, every new-window request (window.open, target=_blank,
 * with or without a gesture) arrives in [onCreateWindow]. Only a tap on a real link (`<a target=_blank>`, also around
 * an image) is followed, and then in the same WebView via [onOpenLink]; everything else, including a script opening
 * a window from a tap on a button or the player, is dropped instead of replacing the current page.
 */
class BrowserChromeClient(
    private val client: BrowserWebViewClient,
    private val onPopupBlocked: () -> Unit = {},
    private val onOpenLink: (String) -> Unit = {},
) : WebChromeClient() {
    override fun onReceivedTitle(
        view: WebView,
        title: String?,
    ) {
        title?.let(client::onReceivedTitle)
    }

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?,
    ): Boolean {
        val hit = view.hitTestResult
        val transport = resultMsg?.obj as? WebView.WebViewTransport
        if (!isUserGesture || hit.type !in LINK_HIT_TYPES || transport == null || isDialog) {
            onPopupBlocked()
            return false
        }
        // The target URL is only known once the new window navigates: hand out a throwaway WebView that reports
        // its first navigation, cancels it (so it loads nothing) and is destroyed. That URL is followed only if it
        // is the tapped link's own href, so a script on the link (onclick="window.open(ad)") cannot swap it out.
        val catcher = WebView(view.context)
        var done = false
        var caught: String? = null
        var href: String? = if (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE) hit.extra.orEmpty() else null
        val finish = {
            if (!done) {
                done = true
                if (caught != null) onPopupBlocked() // timed out waiting for the href
                catcher.destroy()
            }
        }
        val decide = decide@{
            val url = caught ?: return@decide
            val linkHref = href ?: return@decide
            if (done) return@decide
            if (isSameLink(url, linkHref)) onOpenLink(url) else onPopupBlocked()
            done = true
            view.post { catcher.destroy() }
        }
        if (href == null) {
            // An image inside a link: hit.extra is the image, the link's href comes back asynchronously.
            val reply =
                Handler(Looper.getMainLooper()) { msg ->
                    href = msg.data.getString("url").orEmpty()
                    decide()
                    true
                }
            view.requestFocusNodeHref(reply.obtainMessage())
        }
        catcher.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    v: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    if (caught == null) {
                        caught = request.url.toString()
                        decide()
                    }
                    return true
                }
            }
        view.postDelayed(finish, CATCHER_TIMEOUT_MILLIS) // the window never navigated (e.g. about:blank)
        transport.webView = catcher
        resultMsg.sendToTarget()
        return true
    }

    private companion object {
        val LINK_HIT_TYPES = setOf(WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
        const val CATCHER_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * Applies the browser configuration to [webView] and wires [client], [chromeClient] and [captureScript]. Shared by
 * the browser screen and the instrumented capture test so both exercise the same setup.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
fun configureBrowserWebView(
    webView: WebView,
    client: BrowserWebViewClient,
    chromeClient: BrowserChromeClient,
    captureScript: CaptureScript,
) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        setSupportMultipleWindows(true) // so popups reach onCreateWindow, which refuses them
        javaScriptCanOpenWindowsAutomatically = false
        allowFileAccess = false
        allowContentAccess = false
        // No URL lookups with Google from the browser (also disabled app-wide in the manifest).
        safeBrowsingEnabled = false
    }
    // Embedded players often keep their session in third-party cookies.
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    webView.webViewClient = client
    webView.webChromeClient = chromeClient
    // HomeCast downloads nothing; a navigation that turned into a download only ends its redirect allowance.
    webView.setDownloadListener { _, _, _, _, _ -> client.onDownloadStarted() }
    captureScript.install(webView, client)
    webView.setOnTouchListener { _, event ->
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
            client.onUserGesture()
        }
        false
    }
}

/**
 * Whether [url], the first navigation of a new window, is the tapped link's [href] (both absolute http(s) URLs,
 * compared after normalisation and without the fragment).
 */
internal fun isSameLink(
    url: String,
    href: String,
): Boolean {
    fun normalise(raw: String): String? {
        val uri = runCatching { URI(raw.substringBefore('#')).normalize() }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || uri.rawAuthority.isNullOrEmpty()) return null
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://${uri.rawAuthority.lowercase()}${uri.rawPath.ifEmpty { "/" }}$query"
    }
    val a = normalise(url) ?: return false
    return a == normalise(href)
}
