package proto.foliate

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import proto.common.BaseReaderActivity
import proto.common.Probe
import java.io.ByteArrayInputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/** foliate-js in a WebView. Book bytes are served from a ZipFile by a WebViewAssetLoader path handler. */
class ReaderActivity : BaseReaderActivity() {
    private lateinit var web: WebView
    private val zip = CompletableFuture<ZipFile>()
    private var tocDeferred: CompletableDeferred<List<TocItem>>? = null
    private var sink: SearchSink? = null
    private val prefs by lazy { getSharedPreferences("positions", MODE_PRIVATE) }

    private var hostReady = false
    private var chunker: Chunker? = null
    private var openRequested = false

    /** In-place mode: create the WebView and load the host page + foliate-js modules before a book is chosen. */
    override fun prepare() = ensureWebView()

    override fun openBook(path: String) {
        Executors.newSingleThreadExecutor().execute {
            try {
                val z = ZipFile(path)
                if (intent.getBooleanExtra("chunk", false))
                    chunker = runCatching { Chunker(z).apply { run() } }.onFailure { Probe.ev("chunk_failed", "$it") }.getOrNull()
                zip.complete(z)
            } catch (e: Exception) { zip.completeExceptionally(e) }
        }
        ensureWebView()
        if (hostReady) js("openBook()") else openRequested = true
    }

    private fun ensureWebView() {
        if (::web.isInitialized) return
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/meta/", { metaEntries() })
            .addPathHandler("/book/", { p -> bookEntry(p) })
            .build()
        web = WebView(this)
        content.addView(web, 0)
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        WebView.setWebContentsDebuggingEnabled(true)
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(r.url)
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) =
                r.isForMainFrame && r.url.host != "appassets.androidplatform.net"
        }
        web.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                Probe.ev("console", "${m.messageLevel()} ${m.message()} @${m.sourceId()}:${m.lineNumber()}"); return true
            }
        }
        Probe.ev("webview_created")
        web.loadUrl("https://appassets.androidplatform.net/assets/host/reader.html")
    }

    private fun metaEntries(): WebResourceResponse = try {
        val z = zip.get()
        val arr = JSONArray()
        val virtual = chunker?.virtual ?: emptyMap()
        for (e in z.entries()) if (!e.isDirectory && e.name !in virtual)
            arr.put(JSONObject().put("filename", e.name).put("size", e.size))
        for ((name, bytes) in virtual) arr.put(JSONObject().put("filename", name).put("size", bytes.size))
        Probe.ev("zip_index", "entries=${arr.length()}")
        WebResourceResponse("application/json", "utf-8", ByteArrayInputStream(arr.toString().toByteArray()))
    } catch (e: Exception) {
        val msg = (e.cause ?: e).let { "${it.javaClass.simpleName}: ${it.message}" }
        WebResourceResponse("text/plain", "utf-8", 500, "Bad book", null,
            ByteArrayInputStream("Not a readable EPUB archive ($msg)".toByteArray()))
    }

    private fun bookEntry(name: String): WebResourceResponse? {
        val z = runCatching { zip.get() }.getOrNull() ?: return null
        chunker?.virtual?.get(name)?.let { return WebResourceResponse(mime(name), null, ByteArrayInputStream(it)) }
        val e = z.getEntry(name) ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, null)
        return WebResourceResponse(mime(name), null, z.getInputStream(e))
    }

    private fun mime(n: String) = when (n.substringAfterLast('.').lowercase()) {
        "xhtml", "xht" -> "application/xhtml+xml"; "html", "htm" -> "text/html"
        "css" -> "text/css"; "svg" -> "image/svg+xml"; "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
        "xml", "opf", "ncx" -> "application/xml"; "otf" -> "font/otf"; "ttf" -> "font/ttf"
        "woff" -> "font/woff"; "woff2" -> "font/woff2"; "js" -> "text/javascript"
        "mp3" -> "audio/mpeg"; "mp4", "m4v" -> "video/mp4"; else -> "application/octet-stream"
    }

    private fun js(s: String) = runOnUiThread { web.evaluateJavascript(s, null) }

    override fun next() = js("next()")
    override fun prev() = js("prev()")
    override fun applyFontScale(scale: Double) = js("setFont($scale)")
    override fun goTo(key: Any) { Probe.ev("goto"); js("goTo(${JSONObject.quote(key.toString())})") }

    override suspend fun toc(): List<TocItem> {
        val d = CompletableDeferred<List<TocItem>>()
        tocDeferred = d
        js("getToc()")
        return d.await()
    }

    override fun startSearch(query: String, sink: SearchSink) {
        this.sink = sink
        js("search(${JSONObject.quote(query)})")
    }

    override fun cancelSearch() { sink = null; js("cancelSearch && cancelSearch()") }

    override fun onDestroy() {
        web.destroy()
        runCatching { zip.getNow(null)?.close() }
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface fun log(m: String) = Probe.ev("js", m)
        @JavascriptInterface fun hostReady() = runOnUiThread {
            Probe.ev("host_ready"); hostReady = true
            if (openRequested) js("openBook()")
        }
        @JavascriptInterface fun lastLocation(): String = prefs.getString(bookPath, "") ?: ""
        @JavascriptInterface fun opened(info: String) = Probe.ev("book_opened", info)
        @JavascriptInterface fun error(m: String) = runOnUiThread { showError(m) }
        @JavascriptInterface fun relocate(label: String, cfi: String) = runOnUiThread {
            if (cfi.isNotEmpty()) prefs.edit().putString(bookPath, cfi).apply()
            onPageShown(label)
        }
        @JavascriptInterface fun painted(label: String) = Probe.ev("painted_js", label)
        @JavascriptInterface fun tocResult(json: String) {
            val a = JSONArray(json)
            tocDeferred?.complete((0 until a.length()).map {
                val o = a.getJSONObject(it)
                TocItem(o.getString("label"), o.getInt("depth"), o.optString("href"))
            })
        }
        @JavascriptInterface fun searchHits(json: String) {
            val a = JSONArray(json)
            sink?.hits((0 until a.length()).map {
                val o = a.getJSONObject(it)
                Hit(o.optString("label"), o.getString("snippet"), o.getString("cfi"))
            })
        }
        @JavascriptInterface fun searchDone() { sink?.done() }
    }
}
