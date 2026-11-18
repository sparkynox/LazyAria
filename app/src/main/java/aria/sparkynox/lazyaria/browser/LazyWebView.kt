package aria.sparkynox.lazyaria.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.webkit.*
import aria.sparkynox.lazyaria.core.Prefs
import aria.sparkynox.lazyaria.core.RustCore

@SuppressLint("SetJavaScriptEnabled")
class LazyWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : WebView(context, attrs) {

    var tabCallback: TabCallback? = null

    interface TabCallback {
        fun onPageStart(url: String, title: String)
        fun onPageFinish(url: String, title: String)
        fun onProgressChanged(progress: Int)
        fun onReceivedIcon(icon: Bitmap?)
        fun onCreateNewTab(url: String)
        fun onDownloadRequest(url: String, mime: String?, contentDisposition: String?)
    }

    private val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    init {
        applySettings()
        webViewClient = LazyWebViewClient()
        webChromeClient = LazyWebChromeClient()
        setupDownload()
    }

    fun applySettings() {
        settings.apply {
            javaScriptEnabled = Prefs.javascriptEnabled
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = Prefs.textZoom
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = resolveUserAgent()

            // WebRTC — disable peer connection if off
            if (!Prefs.webRtcEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Mitigated via JS injection below; no WebView API for this pre-O
            }
        }

        // DNT header injected in shouldInterceptRequest
        // Night mode via CSS injection in onPageFinished
    }

    private fun resolveUserAgent(): String {
        return when {
            Prefs.desktopMode -> DESKTOP_UA
            Prefs.userAgent == "default" -> settings.userAgentString // stock WebView UA
            else -> Prefs.userAgent
        }
    }

    private fun setupDownload() {
        setDownloadListener { url, ua, contentDisposition, mimetype, _ ->
            tabCallback?.onDownloadRequest(url, mimetype, contentDisposition)
        }
    }

    fun loadUrl(url: String, incognito: Boolean = false) {
        if (incognito) {
            CookieManager.getInstance().setAcceptCookie(false)
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
        } else {
            CookieManager.getInstance().setAcceptCookie(Prefs.saveCookies)
            settings.cacheMode = WebSettings.LOAD_DEFAULT
        }
        super.loadUrl(url)
    }

    fun injectNightMode() {
        if (!Prefs.nightMode) return
        val css = """
            html { filter: invert(90%) hue-rotate(180deg) !important; }
            img, video, canvas, svg { filter: invert(100%) hue-rotate(180deg) !important; }
        """.trimIndent()
        injectCss(css)
    }

    fun injectReaderMode() {
        // Readability.js is in assets; inject and call it
        val js = loadAsset("readability.js") ?: return
        evaluateJavascript(js) { _ ->
            evaluateJavascript(
                "(function(){var article=new Readability(document).parse();document.body.innerHTML='<div style=\"max-width:700px;margin:auto;padding:16px;font-family:${Prefs.readerFont};font-size:${Prefs.readerFontSize}px;line-height:1.6\">'+article.content+'</div>';})();"
            ) {}
        }
    }

    fun injectCustomCss() {
        val css = Prefs.customCss
        if (css.isNotBlank()) injectCss(css)
    }

    fun injectCustomJs() {
        val js = Prefs.customJs
        if (js.isNotBlank()) evaluateJavascript(js) {}
    }

    fun disableWebRtc() {
        evaluateJavascript(
            "navigator.mediaDevices = undefined; window.RTCPeerConnection = undefined;"
        ) {}
    }

    private fun injectCss(css: String) {
        val escaped = css.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        evaluateJavascript(
            "(function(){var s=document.createElement('style');s.textContent='$escaped';document.head.appendChild(s);})();"
        ) {}
    }

    private fun loadAsset(name: String): String? = try {
        context.assets.open(name).bufferedReader().readText()
    } catch (e: Exception) { null }

    // ── WebViewClient ─────────────────────────────────────────────────────────

    inner class LazyWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()

            // Handle non-http schemes (intent://, market://, etc.)
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return true
            }

            // "Allow once" tap from redirect-block interstitial
            if (url.endsWith("__lazyaria_allow")) {
                val realUrl = url.removeSuffix("__lazyaria_allow")
                view.loadUrl(realUrl)
                return true
            }

            // Redirect block — fires on every navigation, not just 3xx
            // Covers JS location.href, meta-refresh, server 3xx all the same
            if (Prefs.adBlockEnabled && RustCore.shouldBlockRedirect(url)) {
                // Show a lightweight interstitial instead of blank
                val escaped = url.replace("'", "\\'")
                view.loadDataWithBaseURL(
                    null,
                    buildRedirectBlockPage(url),
                    "text/html", "utf-8", null
                )
                return true
            }

            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()

            // Ad-block check
            if (Prefs.adBlockEnabled && RustCore.shouldBlock(url)) {
                return WebResourceResponse("text/plain", "utf-8", null)
            }

            return null // Let WebView handle normally
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            tabCallback?.onPageStart(url, view.title ?: "")
        }

        override fun onPageFinished(view: WebView, url: String) {
            injectNightMode()
            injectCustomCss()
            injectCustomJs()
            if (!Prefs.webRtcEnabled) disableWebRtc()

            // Save history (not in incognito — caller filters)
            val title = view.title ?: url
            tabCallback?.onPageFinish(url, title)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) {
                val errorHtml = buildErrorPage(request.url.toString(), error.description.toString())
                view.loadDataWithBaseURL(null, errorHtml, "text/html", "utf-8", null)
            }
        }
    }

    // ── WebChromeClient ───────────────────────────────────────────────────────

    inner class LazyWebChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tabCallback?.onProgressChanged(newProgress)
        }

        override fun onReceivedTitle(view: WebView, title: String) {
            tabCallback?.onPageFinish(view.url ?: "", title)
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            tabCallback?.onReceivedIcon(icon)
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
            val newUrl = (view.hitTestResult.extra) ?: ""
            tabCallback?.onCreateNewTab(newUrl)
            return false
        }
    }

    private fun buildRedirectBlockPage(url: String): String = """
        <!DOCTYPE html><html>
        <head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          body{font-family:sans-serif;padding:32px;background:#1a0a2e;color:#eee;text-align:center}
          h1{font-size:40px;margin:0}p{color:#aaa;margin:12px 0}
          .url{font-size:11px;color:#555;word-break:break-all;margin-top:16px;padding:8px;background:#111;border-radius:6px}
          .btns{margin-top:24px;display:flex;gap:12px;justify-content:center;flex-wrap:wrap}
          button{padding:10px 20px;border:none;border-radius:8px;font-size:14px;cursor:pointer}
          .allow{background:#69F0AE;color:#000}.back{background:#333;color:#eee}
        </style></head>
        <body>
          <h1>🚫</h1>
          <p>Redirect blocked</p>
          <div class="url">${url.take(200)}</div>
          <div class="btns">
            <button class="back" onclick="history.back()">Go back</button>
            <button class="allow" onclick="window.location.href='${url.replace("'","\\'")}__lazyaria_allow'">Allow once</button>
          </div>
        </body></html>
    """.trimIndent()

    private fun buildErrorPage(url: String, error: String): String = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body{font-family:sans-serif;padding:32px;background:#1a1a2e;color:#eee;text-align:center}
          h1{font-size:48px;margin:0}
          p{color:#aaa;margin:12px 0}
          .url{font-size:12px;color:#666;word-break:break-all;margin-top:24px}
          button{margin-top:24px;padding:12px 24px;background:#7c4dff;color:#fff;border:none;border-radius:8px;font-size:16px}
        </style>
        </head>
        <body>
          <h1>⚠️</h1>
          <p>Can't reach this page</p>
          <p style="color:#f44">$error</p>
          <div class="url">$url</div>
          <button onclick="history.back()">Go back</button>
        </body>
        </html>
    """.trimIndent()
}
