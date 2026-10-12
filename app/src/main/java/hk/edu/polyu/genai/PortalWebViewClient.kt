package hk.edu.polyu.genai

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class PortalWebViewClient(
    private val host: Host,
) : WebViewClient() {

    interface Host {
        fun onPortalPageStarted()
        fun onPortalPageFinished()
        fun onPortalPageError(errorCode: Int)
        fun onPortalSslError()
        fun onPortalRenderProcessGone(view: WebView)
        fun openExternal(uri: Uri)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return false
        if (UrlRouting.isWebScheme(uri.scheme)) return false
        host.openExternal(uri)
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        host.onPortalPageStarted()
        view.injectPortalFix()
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        view.injectPortalFix()
    }

    override fun onPageFinished(view: WebView, url: String) {
        view.injectPortalFix()
        host.onPortalPageFinished()
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (!request.isForMainFrame) return
        if (error.errorCode == ERROR_ABORTED) return
        host.onPortalPageError(error.errorCode)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        host.onPortalSslError()
    }

    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
        host.onPortalRenderProcessGone(view)
        return true
    }

    private companion object {
        /** Chromium net::ERR_ABORTED. Fired when a navigation is replaced, including downloads. */
        const val ERROR_ABORTED = -3
    }
}

private fun WebView.injectPortalFix() {
    evaluateJavascript(PortalScripts.SOURCE, null)
}
