package app.epubreader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.ClosedChannelException

/**
 * Owns the single reader WebView for the activity's lifetime. Created and loaded before any book is chosen
 * (pre-warm), so opening a book does not pay for WebView start-up or JS module loading.
 */
class ReaderEngine(private val context: Context, private val server: BookContentServer) : ReaderPort {
    private var view: ReaderWebView? = null
    private val main = Handler(Looper.getMainLooper())
    private val _events = ReaderEvents()
    override val events: SharedFlow<FromWeb> = _events.flow
    private val _ready = MutableStateFlow(false)
    /** True once the host page sent `ready` (events has no replay, so late subscribers wait on this). */
    override val isReady: StateFlow<Boolean> = _ready
    private val _created = MutableStateFlow(false)
    /** True once the WebView exists; MainActivity attaches the surface only then (never during the first frame). */
    val created: StateFlow<Boolean> = _created

    val webView: WebView get() = view ?: create().also { view = it }

    /** F-060: what our selection menu items do, and their titles (set by the activity; may be set before creation). */
    var selectionActions: SelectionActionHandler? = null
        set(v) { field = v; view?.selectionActions = v }
    var selectionLabels: (SelectionAction) -> CharSequence = { it.name }
        set(v) { field = v; view?.selectionLabels = v }

    /** Call after the first library frame has been drawn. Main thread. Throws if the WebView is too old. */
    fun prewarm() { webView; _created.value = true }

    override fun send(m: ToWeb) {
        val js = "window.__reader && window.__reader.receive(${JSONObject.quote(encodeToWeb(m))})"
        // Not View.post: a detached WebView (pre-warm, tests) would queue the runnable until attach.
        main.post { evaluate(js) }
    }

    fun destroy() { view?.destroy(); view = null }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): ReaderWebView {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) { "WEBVIEW_TOO_OLD" }
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/book/", BookPathHandler(server))
            .build()
        val w = ReaderWebView(context)
        w.selectionActions = selectionActions
        w.selectionLabels = selectionLabels
        w.settings.javaScriptEnabled = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(r.url)
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) =
                !allowNavigation(r.url.scheme, r.url.host, r.isForMainFrame)
        }
        // Web-side warnings and errors reach logcat (tag EPRJS); book scripts never run, so this is our own code only.
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                val level = m.messageLevel()
                // foliate sandboxes section frames with allow-scripts (WebKit event bug); the CSP keeps book scripts off (R-007).
                if ((level == ConsoleMessage.MessageLevel.ERROR || level == ConsoleMessage.MessageLevel.WARNING) && "escape its sandboxing" !in m.message())
                    Log.w("EPRJS", "${m.message()} (${m.sourceId()?.substringAfterLast('/')}:${m.lineNumber()})")
                return true
            }
        }
        WebViewCompat.addWebMessageListener(w, "ReaderBridge", setOf("https://$ORIGIN_HOST")) { _, msg, _, _, _ ->
            val text = msg.data ?: return@addWebMessageListener
            val m = decodeFromWeb(text) ?: run {
                // A dropped message (unknown type or wrong shape) would otherwise fail silently, e.g. a lost relocate.
                Log.w("EPR", "bridge: dropped ${text.take(200)}"); Probe.ev("bridge_unknown", text.take(80)); return@addWebMessageListener
            }
            if (m is FromWeb.Ready) { _ready.value = true; Probe.ev("reader_ready") }
            _events.emit(m)
        }
        Probe.ev("webview_created")
        w.loadUrl(HOST_URL)
        return w
    }

    /** Main thread only (the Handler posts to it); a no-op once destroyed. */
    private fun evaluate(js: String) { view?.evaluateJavascript(js, null) }

    companion object {
        const val ORIGIN_HOST = "appassets.androidplatform.net"
        val HOST_URL: String = Uri.parse("https://$ORIGIN_HOST/assets/reader/index.html").toString()
    }
}

/**
 * Serves "/book/<sessionId>/<entry>" on WebView I/O threads. Nothing may escape from here or from the body stream:
 * an exception on those threads would crash the WebView (and the app). A session closed under a running request
 * (ClosedChannelException) answers 410; damaged entries (ZipFormatException) and other read failures answer 500.
 */
private class BookPathHandler(private val server: BookContentServer) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse = try {
        val r = server.serve(path)
        val body = if (r.status == 200) GuardedStream(r.body()) else ByteArrayInputStream(ByteArray(0))
        response(r.status, r.reason, r.mime, body)
    } catch (e: ClosedChannelException) {
        response(410, "Gone", "text/plain", ByteArrayInputStream(ByteArray(0)))
    } catch (e: Exception) {
        Log.w("EPR", "book request failed: $path: $e")
        response(500, "Internal Server Error", "text/plain", ByteArrayInputStream(ByteArray(0)))
    } catch (e: Throwable) { // last guard (M8 review I1): an Error here would crash the app on a WebView thread
        Log.e("EPR", "book request failed: $path", e)
        response(500, "Internal Server Error", "text/plain", ByteArrayInputStream(ByteArray(0)))
    }

    private fun response(status: Int, reason: String, mime: String, body: InputStream) =
        WebResourceResponse(mime, null, status, reason, BOOK_RESPONSE_HEADERS, body)
}

/**
 * Headers of every /book/ response. Sections are shown from blob: documents under the host page's CSP; this CSP
 * also covers a book file loaded directly (defense in depth, R-007): its scripts and plugins never run.
 */
internal val BOOK_RESPONSE_HEADERS = mapOf(
    "Cache-Control" to "no-store",
    "Content-Security-Policy" to "script-src 'none'; object-src 'none'",
)

/**
 * Book sections render in blob: subframes (foliate); everything else must stay on our https origin. The main frame
 * may only ever show our host page (https + our host); a book link never replaces it.
 */
internal fun allowNavigation(scheme: String?, host: String?, isMainFrame: Boolean): Boolean {
    val ours = scheme == "https" && host == ReaderEngine.ORIGIN_HOST
    return ours || (!isMainFrame && scheme == "blob")
}

/** Reads happen later, inside WebView; only IOException is handled there, so every failure is reported as one. */
internal class GuardedStream(private val inner: InputStream) : InputStream() {
    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException(e)
    }
    override fun read(): Int = guard { inner.read() }
    override fun read(b: ByteArray, off: Int, len: Int): Int = guard { inner.read(b, off, len) }
    override fun available(): Int = guard { inner.available() }
    override fun close() = guard { inner.close() }
}
