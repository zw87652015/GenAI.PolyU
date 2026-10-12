package hk.edu.polyu.genai

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class MainActivity : AppCompatActivity(), PortalWebViewClient.Host {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var toolbar: MaterialToolbar
    private lateinit var errorPanel: View
    private lateinit var errorText: TextView

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private var pendingWebRequest: PermissionRequest? = null
    private var pendingWebResources: Array<String>? = null
    private var pendingDownload: DownloadRequest? = null
    private var clearHistoryOnNextPage = false

    private val chromeClient = PortalChromeClient()

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileCallback
            val camera = cameraUri
            fileCallback = null
            cameraUri = null
            if (callback == null) return@registerForActivityResult
            val parsed = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            when {
                parsed != null && parsed.isNotEmpty() -> callback.onReceiveValue(parsed)
                result.resultCode == RESULT_OK && camera != null -> callback.onReceiveValue(arrayOf(camera))
                else -> callback.onReceiveValue(null)
            }
        }

    private val webPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val request = pendingWebRequest
            val resources = pendingWebResources
            pendingWebRequest = null
            pendingWebResources = null
            if (request == null || resources == null) return@registerForActivityResult
            if (granted.values.all { it }) request.grant(resources) else request.deny()
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val request = pendingDownload
            pendingDownload = null
            if (request == null) return@registerForActivityResult
            if (granted) startDownload(request) else {
                Toast.makeText(this, R.string.download_unsupported, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        WebView.setWebContentsDebuggingEnabled(isDebuggable())
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        progress = findViewById(R.id.progress)
        errorPanel = findViewById(R.id.errorPanel)
        errorText = findViewById(R.id.errorText)
        setSupportActionBar(toolbar)

        webView = findViewById(R.id.web)
        bindWebView(webView)
        findViewById<View>(R.id.retryButton).setOnClickListener { retry() }
        onBackPressedDispatcher.addCallback(this) { goBack() }

        val restored = savedInstanceState?.let { webView.restoreState(it) } != null
        if (!restored) webView.loadUrl(initialUrl(intent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = linkedUrl(intent) ?: return
        webView.loadUrl(url)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onStop() {
        CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_refresh -> retry()
            R.id.action_home -> {
                errorPanel.visibility = View.GONE
                webView.loadUrl(Portal.HOME_URL)
            }
            R.id.action_sign_out -> confirmSignOut()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onPortalPageStarted() {
        errorPanel.visibility = View.GONE
        progress.visibility = View.VISIBLE
        progress.progress = 0
    }

    override fun onPortalPageFinished() {
        if (!clearHistoryOnNextPage) return
        clearHistoryOnNextPage = false
        webView.clearHistory()
    }

    override fun onPortalPageError(errorCode: Int) {
        val message = when (errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP,
            WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_TIMEOUT,
            WebViewClient.ERROR_IO,
            -> getString(R.string.error_offline)
            else -> getString(R.string.error_generic)
        }
        showError(message)
    }

    override fun onPortalSslError() {
        showError(getString(R.string.ssl_error))
    }

    override fun onPortalRenderProcessGone(view: WebView) {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        val parent = view.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(view)
        val params = view.layoutParams
        parent.removeView(view)
        view.destroy()
        val replacement = WebView(this)
        replacement.id = R.id.web
        parent.addView(replacement, index, params)
        webView = replacement
        bindWebView(replacement)
        replacement.loadUrl(Portal.HOME_URL)
    }

    override fun openExternal(uri: Uri) {
        try {
            if (uri.scheme.equals("intent", ignoreCase = true)) {
                launchIntentUri(uri)
                return
            }
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindWebView(target: WebView) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(target, true)
        with(target.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = true
            loadsImagesAutomatically = true
            // Drop the WebView token so campus sign-in pages treat this as Chrome.
            userAgentString = userAgentString.replace("; wv", "")
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(target.settings, false)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(target, PortalScripts.SOURCE, setOf("*"))
        }
        target.webViewClient = PortalWebViewClient(this)
        target.webChromeClient = chromeClient
        target.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            enqueueDownload(url, userAgent, contentDisposition, mimeType)
        }
        target.requestFocus()
    }

    private fun goBack() {
        if (errorPanel.visibility == View.VISIBLE) {
            errorPanel.visibility = View.GONE
            if (webView.canGoBack()) webView.goBack() else webView.loadUrl(Portal.HOME_URL)
            return
        }
        if (webView.canGoBack()) webView.goBack() else finish()
    }

    private fun retry() {
        errorPanel.visibility = View.GONE
        val url = webView.url
        if (url.isNullOrBlank() || url == "about:blank") webView.loadUrl(Portal.HOME_URL)
        else webView.reload()
    }

    private fun showError(message: String) {
        errorText.text = message
        errorPanel.visibility = View.VISIBLE
        progress.visibility = View.GONE
    }

    private fun confirmSignOut() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sign_out_title)
            .setMessage(R.string.sign_out_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.sign_out_confirm) { _, _ -> signOut() }
            .show()
    }

    private fun signOut() {
        webView.stopLoading()
        CookieManager.getInstance().removeAllCookies {
            webView.post {
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                webView.clearCache(true)
                errorPanel.visibility = View.GONE
                clearHistoryOnNextPage = true
                webView.loadUrl(Portal.HOME_URL)
            }
        }
    }

    private fun showFileChooser(
        filePathCallback: ValueCallback<Array<Uri>>?,
        params: WebChromeClient.FileChooserParams?,
    ): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = filePathCallback
        val content = try {
            params?.createIntent()
        } catch (_: Exception) {
            null
        } ?: Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val camera = createCameraIntent()
        val chooser = Intent.createChooser(content, getString(R.string.choose_file)).apply {
            if (camera != null) putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
        }
        return try {
            fileChooserLauncher.launch(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
            false
        }
    }

    private fun createCameraIntent(): Intent? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        cameraUri = uri
        val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newUri(contentResolver, "capture", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        return if (capture.resolveActivity(packageManager) != null) capture else null
    }

    private fun grantWebPermission(request: PermissionRequest) {
        val webGrants = mutableListOf<String>()
        val androidNeeded = mutableListOf<String>()
        for (resource in request.resources) {
            when (resource) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                    webGrants += resource
                    if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                        androidNeeded += Manifest.permission.RECORD_AUDIO
                    }
                }
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                    webGrants += resource
                    if (!hasPermission(Manifest.permission.CAMERA)) {
                        androidNeeded += Manifest.permission.CAMERA
                    }
                }
            }
        }
        if (webGrants.isEmpty()) {
            request.deny()
            return
        }
        val resources = webGrants.toTypedArray()
        if (androidNeeded.isEmpty()) {
            request.grant(resources)
            return
        }
        pendingWebRequest?.deny()
        pendingWebRequest = request
        pendingWebResources = resources
        webPermissionLauncher.launch(androidNeeded.toTypedArray())
    }

    private fun enqueueDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
    ) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            Toast.makeText(this, R.string.download_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        val request = DownloadRequest(url, userAgent, contentDisposition, mimeType)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            pendingDownload = request
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        startDownload(request)
    }

    private fun startDownload(request: DownloadRequest) {
        try {
            val name = URLUtil.guessFileName(request.url, request.contentDisposition, request.mimeType)
            val download = DownloadManager.Request(Uri.parse(request.url)).apply {
                setMimeType(request.mimeType)
                addRequestHeader("User-Agent", request.userAgent)
                CookieManager.getInstance().getCookie(request.url)?.let { addRequestHeader("Cookie", it) }
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            getSystemService(DownloadManager::class.java).enqueue(download)
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (_: IllegalArgumentException) {
            Toast.makeText(this, R.string.download_unsupported, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.download_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    private fun launchIntentUri(uri: Uri) {
        try {
            val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else if (!fallback.isNullOrBlank() && UrlRouting.isWebScheme(Uri.parse(fallback).scheme)) {
                webView.loadUrl(fallback)
            } else {
                Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
            }
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        } catch (_: java.net.URISyntaxException) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isDebuggable(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun initialUrl(source: Intent?): String = linkedUrl(source) ?: Portal.HOME_URL

    private fun linkedUrl(source: Intent?): String? {
        val data = source?.data ?: return null
        if (!data.scheme.equals("https", ignoreCase = true)) return null
        if (!data.host.equals("genai.polyu.edu.hk", ignoreCase = true)) return null
        return data.toString()
    }

    private inner class PortalChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progress.progress = newProgress
            progress.visibility = if (newProgress in 0..99) View.VISIBLE else View.GONE
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            if (title.isNullOrBlank() || title.startsWith("http")) return
            toolbar.title = title
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?,
        ): Boolean = showFileChooser(filePathCallback, fileChooserParams)

        override fun onPermissionRequest(request: PermissionRequest) {
            grantWebPermission(request)
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingWebRequest === request) {
                pendingWebRequest = null
                pendingWebResources = null
            }
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            val popup = WebView(view.context)
            var consumed = false
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    popupView: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    openOnce(request.url?.toString(), popupView)
                    return true
                }

                override fun onPageStarted(popupView: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    openOnce(url, popupView)
                }

                private fun openOnce(url: String?, popupView: WebView) {
                    if (consumed || url.isNullOrBlank() || url == "about:blank") return
                    consumed = true
                    view.loadUrl(url)
                    popupView.post {
                        popupView.stopLoading()
                        popupView.destroy()
                    }
                }
            }
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }
    }

    private data class DownloadRequest(
        val url: String,
        val userAgent: String,
        val contentDisposition: String,
        val mimeType: String,
    )
}
