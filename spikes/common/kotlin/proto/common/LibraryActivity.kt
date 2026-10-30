package proto.common

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File

/** Minimal "library": one button per EPUB found under /sdcard/Books. */
class LibraryActivity : Activity() {
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 160, 0, 0) }
        val root = File("/sdcard/Books")
        if (intent.getBooleanExtra("reset", false))
            getSharedPreferences("positions", MODE_PRIVATE).edit().clear().commit()
        val all = root.walkTopDown().filter { it.isFile && it.name.endsWith(".epub", true) }
            .sortedBy { it.path }.toList()
        // "only": comma-separated file names, shown in that order (used by the measurement drivers)
        val only = intent.getStringExtra("only")?.split(',')
        val books = only?.mapNotNull { n -> all.firstOrNull { it.name == n } } ?: all
        for (f in books) {
            list.addView(Button(this).apply {
                text = f.relativeTo(root).path
                isAllCaps = false
                setOnTouchListener { _, e ->
                    if (e.action == MotionEvent.ACTION_DOWN) Probe.ev("tap_down", "book=${f.name}")
                    false
                }
                setOnClickListener {
                    Probe.ev("tap_click", "book=${f.name}")
                    startActivity(readerIntent(f.path))
                }
            })
        }
        setContentView(ScrollView(this).apply { addView(list) })
        Probe.ev("library_shown", "count=${books.size}")
        // Optional: initialise Chromium and its renderer process while the library is on screen,
        // as a real app would, so the first book open does not pay for WebView start-up.
        if (intent.getBooleanExtra("prewarm", false) && warm == null) list.post {
            warm = android.webkit.WebView(applicationContext).apply { loadUrl("about:blank") }
            Probe.ev("webview_prewarmed")
        }
    }

    companion object { private var warm: android.webkit.WebView? = null }

    private fun readerIntent(path: String) =
        Intent().setClassName(this, "$packageName.ReaderActivity").putExtra("path", path)
}
