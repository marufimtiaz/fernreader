package app.epubreader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.epubreader.bench.Probe
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import app.epubreader.reader.bridge.decodeFromWeb
import app.epubreader.reader.bridge.encodeToWeb
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Owns the single reader WebView for the activity's lifetime. Created and loaded before any book is chosen
 * (pre-warm), so opening a book does not pay for WebView start-up or JS module loading.
 */
class ReaderEngine(private val context: Context) {
    private var view: WebView? = null
    private val main = Handler(Looper.getMainLooper())
    private val _events = MutableSharedFlow<FromWeb>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<FromWeb> = _events
    private val _ready = MutableStateFlow(false)
    /** True once the host page sent `ready` (events has no replay, so late subscribers wait on this). */
    val isReady: StateFlow<Boolean> = _ready

    val webView: WebView get() = view ?: create().also { view = it }

    /** Call after the first library frame has been drawn. Main thread. */
    fun prewarm() { webView }

    fun send(m: ToWeb) {
        val js = "window.__reader && window.__reader.receive(${JSONObject.quote(encodeToWeb(m))})"
        // Not View.post: a detached WebView (pre-warm, tests) would queue the runnable until attach.
        main.post { webView.evaluateJavascript(js, null) }
    }

    fun destroy() { view?.destroy(); view = null }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) { "WEBVIEW_TOO_OLD" }
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        val w = WebView(context)
        w.settings.javaScriptEnabled = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(r.url)
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = r.url.host != ORIGIN_HOST
        }
        WebViewCompat.addWebMessageListener(w, "ReaderBridge", setOf("https://$ORIGIN_HOST")) { _, msg, _, _, _ ->
            val text = msg.data ?: return@addWebMessageListener
            val m = decodeFromWeb(text) ?: run { Probe.ev("bridge_unknown", text.take(80)); return@addWebMessageListener }
            if (m is FromWeb.Ready) { _ready.value = true; Probe.ev("reader_ready") }
            _events.tryEmit(m)
        }
        Probe.ev("webview_created")
        w.loadUrl(HOST_URL)
        return w
    }

    companion object {
        const val ORIGIN_HOST = "appassets.androidplatform.net"
        val HOST_URL: String = Uri.parse("https://$ORIGIN_HOST/assets/reader/index.html").toString()
    }
}
