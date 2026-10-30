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
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val root = File("/sdcard/Books")
        val books = root.walkTopDown().filter { it.isFile && it.name.endsWith(".epub", true) }
            .sortedBy { it.path }.toList()
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
    }

    private fun readerIntent(path: String) =
        Intent().setClassName(this, "$packageName.ReaderActivity").putExtra("path", path)
}
