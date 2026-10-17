package io.github.yunuserdemsihhatli.homecast.browser

import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import io.github.yunuserdemsihhatli.homecast.capture.CandidateStore
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the browser's single WebView for the lifetime of the activity. The browser screen only attaches it to and
 * detaches it from the Compose tree, so leaving the screen (e.g. for the remote) and coming back shows the very same
 * page, player state and history without a reload. Only [destroy] (activity finishing) or a dead renderer ends a
 * WebView; after a renderer crash a fresh one replaces it, reloading the page [viewModel] was on.
 */
class BrowserHost(
    private val context: Context,
    private val blocklist: Deferred<HostBlocklist>,
    private val store: CandidateStore,
    private val viewModel: BrowserViewModel,
) {
    private val captureScript = CaptureScript.load(context)

    lateinit var webView: WebView
        private set

    lateinit var client: BrowserWebViewClient
        private set

    private val _generation = MutableStateFlow(0)

    /** Bumped whenever [webView] is replaced, so the UI attaches the new one. */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    init {
        createWebView()
    }

    /** Loads a URL the user asked for, letting its redirects past the redirect guard. */
    fun load(url: String) {
        client.onUserNavigation(url)
        webView.loadUrl(url)
    }

    fun goBack() {
        if (!webView.canGoBack()) return
        client.onUserNavigation(historyUrl(-1))
        webView.goBack()
    }

    fun goForward() {
        if (!webView.canGoForward()) return
        client.onUserNavigation(historyUrl(+1))
        webView.goForward()
    }

    fun reload() {
        client.onUserNavigation(webView.url)
        webView.reload()
    }

    /** URL of the history entry [offset] steps from the current one. */
    private fun historyUrl(offset: Int): String? {
        val history = webView.copyBackForwardList()
        return history.getItemAtIndex(history.currentIndex + offset)?.url
    }

    /** Pauses the page's own videos and audio, in every frame: the user is watching on the TV now. */
    fun pausePageMedia() = client.pausePageMedia(webView)

    /** The activity is visible again: let the page run. Main thread. */
    fun onStart() {
        webView.onResume()
        // JavaScript timers are paused and resumed for every WebView of the process at once.
        if (visibleHosts++ == 0) webView.resumeTimers()
    }

    /**
     * The activity is no longer visible: pause the page (timers, animations) so it does not run in the background.
     * Timers are only paused once no browser is visible: an activity's onStop can follow the next one's onStart.
     */
    fun onStop() {
        webView.onPause()
        visibleHosts = (visibleHosts - 1).coerceAtLeast(0)
        if (visibleHosts == 0) webView.pauseTimers()
    }

    /** Moves [webView] into [container] (taking it from wherever it was shown before). */
    fun attachTo(container: ViewGroup) {
        val view = webView
        if (view.parent === container) return
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    /**
     * Takes [webView] out of [container] if it is still there. A container released late (after a new screen already
     * attached the WebView elsewhere) must not pull it out of the new one.
     */
    fun detachFrom(container: ViewGroup) {
        val view = webView
        if (view.parent === container) container.removeView(view)
    }

    fun destroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun createWebView() {
        val view = WebView(context)
        val newClient =
            BrowserWebViewClient(
                blocklist = blocklist,
                redirectGuard = RedirectGuard(),
                store = store,
                events = viewModel,
                userAgent = view.settings.userAgentString,
                onRendererGone = ::replaceDeadWebView,
            )
        configureBrowserWebView(
            webView = view,
            client = newClient,
            chromeClient =
                BrowserChromeClient(newClient, onPopupBlocked = viewModel::onBlocked, onOpenLink = ::load),
            captureScript = captureScript,
        )
        webView = view
        client = newClient
        // A new WebView (activity recreated, or renderer replaced) shows the page the user was on.
        viewModel.state.value.url
            .takeIf { it.isNotEmpty() }
            ?.let(view::loadUrl)
    }

    private companion object {
        /** Started (visible) hosts in the process; main thread only. */
        var visibleHosts = 0
    }

    private fun replaceDeadWebView() {
        destroy()
        createWebView()
        _generation.value++
    }
}
