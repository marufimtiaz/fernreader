package proto.common

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Engine-independent reader shell: a floating control bar (TOC, search, text size, page label),
 * a debug command receiver for the measurement drivers, and timestamped events.
 *
 * Commands: adb shell am broadcast -a <pkg>.CMD --es cmd <name> [--es arg <value>]
 *   next | prev | fontUp | fontDown | font <scale> | toc <label substring or #index> |
 *   search <query> | hit <index> | rotate land|port | back
 */
abstract class BaseReaderActivity : AppCompatActivity() {
    data class TocItem(val label: String, val depth: Int, val key: Any)
    data class Hit(val label: String, val snippet: String, val key: Any)

    interface SearchSink {
        fun hits(batch: List<Hit>)
        fun done()
    }

    protected lateinit var content: FrameLayout
    private lateinit var pageLabel: TextView
    private lateinit var marker: View
    private lateinit var spinner: ProgressBar
    private var firstPageSeen = false
    protected var fontScale = 1.0
    protected val bookPath: String by lazy { intent.getStringExtra("path") ?: intent.data?.path ?: "" }

    abstract fun openBook(path: String)
    abstract fun next()
    abstract fun prev()
    abstract fun applyFontScale(scale: Double)
    abstract suspend fun toc(): List<TocItem>
    abstract fun goTo(key: Any)
    abstract fun startSearch(query: String, sink: SearchSink)
    open fun cancelSearch() {}

    override fun onCreate(savedInstanceState: Bundle?) {
        Probe.ev("reader_create", "book=${bookPath.substringAfterLast('/')}")
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        content = FrameLayout(this).apply { id = View.generateViewId() }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(200, 240, 240, 240))
            gravity = Gravity.CENTER_VERTICAL
        }
        fun btn(t: String, f: () -> Unit) = bar.addView(Button(this).apply {
            text = t; isAllCaps = false; minWidth = 0; setOnClickListener { f() }
        })
        btn("TOC") { showToc() }
        btn("Find") { askSearch() }
        btn("A−") { changeFont(-0.1) }
        btn("A+") { changeFont(+0.1) }
        pageLabel = TextView(this).apply { setPadding(16, 0, 16, 0) }
        bar.addView(pageLabel)
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        // Visible marker toggled on every debug command: gives screen recordings a "tap" frame.
        marker = View(this).apply { setBackgroundColor(Color.BLACK); visibility = View.INVISIBLE }
        val m = (100 * resources.displayMetrics.density).toInt()
        root.addView(marker, FrameLayout.LayoutParams(m, m, Gravity.TOP or Gravity.START))
        spinner = ProgressBar(this)
        root.addView(spinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)
        root.post { Probe.ev("reader_frame") }
        ContextCompat.registerReceiver(this, receiver, IntentFilter("$packageName.CMD"),
            ContextCompat.RECEIVER_EXPORTED)
        openBook(bookPath)
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Probe.ev("config_changed", "orientation=${newConfig.orientation}")
    }

    /** Engines call this whenever a page is displayed; logs "painted" after the next two frames. */
    protected fun onPageShown(label: String, what: String = "page") {
        pageLabel.text = label
        if (!firstPageSeen) {
            firstPageSeen = true
            spinner.visibility = View.GONE
            Probe.ev("first_page_cb", label)
        }
        Probe.ev("page_cb", "$what $label")
        Choreographer.getInstance().postFrameCallback {
            Choreographer.getInstance().postFrameCallback { Probe.ev("painted", "$what $label") }
        }
    }

    protected fun updateLabel(label: String) { pageLabel.text = label }

    protected fun showError(msg: String) {
        Probe.ev("open_error", msg.replace('\n', ' ').take(200))
        spinner.visibility = View.GONE
        content.removeAllViews()
        content.addView(TextView(this).apply {
            text = "This book could not be opened.\n\n$msg"
            setPadding(48, 200, 48, 48); textSize = 18f
        })
    }

    private fun changeFont(d: Double) {
        fontScale = (fontScale + d).coerceIn(0.8, 2.6)
        Probe.ev("font_cmd", "scale=%.1f".format(fontScale))
        applyFontScale(fontScale)
    }

    private fun showToc() {
        lifecycleScope.launch {
            val items = toc()
            val labels = items.map { "  ".repeat(it.depth) + it.label }
            AlertDialog.Builder(this@BaseReaderActivity).setTitle("Contents")
                .setItems(labels.toTypedArray()) { _, i -> goTo(items[i].key) }.show()
        }
    }

    private fun askSearch() {
        val et = EditText(this)
        AlertDialog.Builder(this).setTitle("Search").setView(et)
            .setPositiveButton("Search") { _, _ -> runSearch(et.text.toString()) }.show()
    }

    private val hits = mutableListOf<Hit>()
    private var hitAdapter: ArrayAdapter<String>? = null
    private var searchDialog: AlertDialog? = null

    private fun runSearch(q: String) {
        cancelSearch()
        hits.clear()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        hitAdapter = adapter
        val lv = ListView(this).apply { this.adapter = adapter }
        val dlg = AlertDialog.Builder(this).setTitle("Searching “$q”…").setView(lv)
            .setOnDismissListener { cancelSearch() }.create()
        lv.setOnItemClickListener { _, _, i, _ -> dlg.dismiss(); goTo(hits[i].key) }
        searchDialog?.dismiss(); searchDialog = dlg; dlg.show()
        Probe.ev("search_start", "q=$q")
        var first = true
        startSearch(q, object : SearchSink {
            override fun hits(batch: List<Hit>) = runOnUiThread {
                if (batch.isEmpty()) return@runOnUiThread
                hits += batch
                adapter.addAll(batch.map { "${it.label}\n${it.snippet}" })
                dlg.setTitle("“$q”: ${hits.size}…")
                if (first) { first = false; Probe.ev("search_first", "n=${hits.size}") }
            }
            override fun done() = runOnUiThread {
                dlg.setTitle("“$q”: ${hits.size} results")
                Probe.ev("search_done", "n=${hits.size}")
            }
        })
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val cmd = i.getStringExtra("cmd") ?: return
            val arg = i.getStringExtra("arg") ?: ""
            marker.visibility = if (marker.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
            Probe.ev("cmd", "$cmd $arg")
            when (cmd) {
                "next" -> next()
                "prev" -> prev()
                "fontUp" -> changeFont(+0.1)
                "fontDown" -> changeFont(-0.1)
                "font" -> { fontScale = arg.toDouble(); applyFontScale(fontScale) }
                "toc" -> lifecycleScope.launch {
                    val items = toc()
                    val item = if (arg.startsWith("#")) items.getOrNull(arg.drop(1).toInt())
                    else items.lastOrNull { it.label.contains(arg, ignoreCase = true) }
                    Probe.ev("toc_target", item?.label ?: "NOT FOUND")
                    item?.let { goTo(it.key) }
                }
                "search" -> runSearch(arg)
                "hit" -> hits.getOrNull(arg.toInt())?.let { searchDialog?.dismiss(); goTo(it.key) }
                "rotate" -> requestedOrientation = if (arg == "land")
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                "back" -> finish()
            }
        }
    }
}
