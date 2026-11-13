package app.epubreader.reader

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.epubreader.MainActivity
import app.epubreader.core.data.Flow
import app.epubreader.core.data.ReaderSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * C-111: in the fullscreen reader the system bars showing or hiding moves nothing: the page's edges and the first and last
 * text lines keep their places on screen, the bars draw over a band the page keeps free (their size whether shown or not),
 * and no text shows under the bars. C-112: there is no page-number line; the page (paged flow's text area included) runs
 * down to that fixed bottom inset. On gesture and on button navigation (set before the
 * activity starts and put back afterwards, as in EdgeContentsTest), with the bars brought in by a top swipe (C-104) and
 * while Contents is being pulled in from the right edge (the owner's case: the bars appear during the swipe; the test brings
 * them in there as the system does, since the emulator's system doesn't on its own).
 */
@RunWith(AndroidJUnit4::class)
class FullscreenBarsTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)
    private val w get() = dev.displayWidth
    private val hgt get() = dev.displayHeight

    private fun shell(cmd: String): String {
        val fd = inst.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }.trim()
    }
    private fun navMode() = shell("settings get secure navigation_mode")
    private fun setNav(mode: String) {
        shell("cmd overlay enable com.android.internal.systemui.navbar.$mode")
        val want = if (mode == GESTURAL) "2" else "0"
        val until = SystemClock.uptimeMillis() + 10_000
        while (navMode() != want) { check(SystemClock.uptimeMillis() < until) { "navigation mode stayed ${navMode()}" }; Thread.sleep(100) }
        Thread.sleep(2_500) // SystemUI rebuilds its bar and its Back detector after the setting
    }

    @Before fun liftAStrayFinger() {
        val now = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 0f, 0f, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        inst.uiAutomation.injectInputEvent(e, true)
        e.recycle()
    }

    /** One finger, moved in steps (as in EdgeContentsTest). */
    private inner class Finger(var x: Int, var y: Int) {
        private val down = SystemClock.uptimeMillis()
        var lifted = false
        private fun send(action: Int) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            assertTrue(inst.uiAutomation.injectInputEvent(e, true))
            e.recycle()
        }
        init { send(MotionEvent.ACTION_DOWN) }
        suspend fun move(toX: Int, toY: Int, steps: Int) {
            val x0 = x; val y0 = y
            for (s in 1..steps) { x = x0 + (toX - x0) * s / steps; y = y0 + (toY - y0) * s / steps; send(MotionEvent.ACTION_MOVE); delay(16) }
        }
        fun up() { if (!lifted) { send(MotionEvent.ACTION_UP); lifted = true } }
    }

    private fun run(nav: String, flow: Flow = Flow.PAGED, block: suspend (ReaderHarness) -> Unit): Unit = runBlocking {
        val before = navMode()
        val wp = warAndPeace()
        try {
            setNav(nav)
            ActivityScenario.launch(MainActivity::class.java).use { sc ->
                val h = ReaderHarness(sc, this)
                try {
                    h.ready()
                    h.a.controller.updateSettings { ReaderSettings(flow = flow, fullscreen = true) }
                    h.a.settingsStored()
                    h.open(wp)
                    val toc = h.a.controller.state.value.toc.filter { !it.missing && it.index >= 0 }
                    h.tocJump(toc[toc.size / 3].label)
                    h.send(app.epubreader.reader.bridge.ToWeb.Next()) // a full page of text, not a chapter head
                    h.quiet()
                    h.awaitBars(false)
                    delay(SETTLE_MS)
                    block(h)
                } finally {
                    sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                    h.a.settingsStored()
                    h.stop()
                }
            }
        } finally {
            setNav(if (before == "2") GESTURAL else THREE_BUTTON)
        }
    }

    private fun ReaderHarness.insets(): WindowInsetsCompat? {
        var i: WindowInsetsCompat? = null
        sc.onActivity { i = ViewCompat.getRootWindowInsets(it.window.decorView) }
        return i
    }
    private fun ReaderHarness.barsVisible() = insets()?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: false
    private suspend fun ReaderHarness.awaitBars(visible: Boolean) {
        val until = SystemClock.uptimeMillis() + 3_000
        while (barsVisible() != visible) { assertTrue("the bars ${if (visible) "shown" else "hidden"}", SystemClock.uptimeMillis() < until); delay(30) }
    }

    /**
     * Where things are on screen (px): the page (the WebView) and paged flow's text area in it, the first and last text lines
     * on the page, the bars when shown, and the fixed bottom inset (the navigation bar's size whether shown or not, C-111).
     */
    private data class Layout(
        val pageTop: Int, val pageBottom: Int, val areaBottom: Int, val textTop: Int, val textBottom: Int, val lines: Int,
        val barsTop: Int, val barsBottom: Int, val fixedBottom: Int,
    )

    private suspend fun ReaderHarness.layout(): Layout {
        val web = IntArray(2); var webHeight = 0; var rootBottom = 0
        sc.onActivity { a ->
            a.reader.webView.getLocationOnScreen(web); webHeight = a.reader.webView.height
            val root = IntArray(2).also(a.window.decorView::getLocationOnScreen)
            rootBottom = root[1] + a.window.decorView.height
        }
        val t = JSONObject(JSONTokener(sc.js(TEXT_EXTENT)).nextValue() as String)
        val dpr = t.getDouble("dpr")
        fun px(css: Double) = web[1] + Math.round(css * dpr).toInt()
        val i = insets()!!
        val bars = i.getInsets(WindowInsetsCompat.Type.systemBars())
        val fixed = i.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        return Layout(
            web[1], web[1] + webHeight, px(t.getDouble("area")), px(t.getDouble("top")), px(t.getDouble("bottom")),
            t.getInt("n"), bars.top, hgt - bars.bottom, rootBottom - fixed.bottom,
        )
    }

    /**
     * With the bars shown after [hidden] was taken: nothing moved, the page reaches down to the fixed bottom inset and no
     * further (C-112: no band is kept for a page-number line), and no text under the bars.
     */
    private fun assertSame(how: String, hidden: Layout, shown: Layout, paged: Boolean = true) {
        val msg = "$how\nhidden $hidden\nshown  $shown"
        assertTrue("text on the page: $msg", hidden.lines > 0 && shown.lines > 0)
        assertTrue("the bars were shown: $msg", shown.barsTop > 0 && shown.barsBottom < hgt)
        assertEquals("the page's top: $msg", hidden.pageTop, shown.pageTop)
        assertEquals("the page's bottom: $msg", hidden.pageBottom, shown.pageBottom)
        for (l in listOf(hidden, shown)) {
            assertEquals("the page ends at the fixed bottom inset: $msg", l.fixedBottom, l.pageBottom)
            assertTrue("no text below the page: $msg", l.textBottom <= l.pageBottom)
            // Paged flow's text area (foliate's paged container) takes the room the line had: down to the page's end.
            if (paged) assertTrue("the text area runs down to the fixed bottom inset: $msg", abs(l.areaBottom - l.pageBottom) <= 1)
        }
        assertTrue("no text under the navigation bar: $msg", shown.textBottom <= shown.barsBottom)
        if (!paged) return
        assertTrue("the first text line kept its place: $msg", abs(hidden.textTop - shown.textTop) <= 1)
        assertTrue("the last text line kept its place: $msg", abs(hidden.textBottom - shown.textBottom) <= 1)
        assertTrue("no text under the status bar: $msg", shown.textTop >= shown.barsTop)
    }

    /** C-104's top swipe: the bars come in over the page. */
    private suspend fun topSwipe(h: ReaderHarness, flow: Flow = Flow.PAGED) {
        val hidden = h.layout()
        dev.swipe(w / 2, 1, w / 2, hgt / 3, 20)
        h.awaitBars(true)
        delay(SETTLE_MS)
        assertSame("top swipe", hidden, h.layout(), paged = flow == Flow.PAGED)
    }

    /**
     * Contents pulled in from the right edge and held half way, the bars coming in meanwhile (the owner's screenshot).
     * [angled]: on gesture navigation a straight pull is the system's Back; an angled one reaches the app (C-103).
     */
    private suspend fun contentsSwipe(h: ReaderHarness, angled: Boolean) {
        val hidden = h.layout()
        val f = Finger(w - 12, hgt * 2 / 5)
        try {
            if (angled) f.move(w * 2 / 5, hgt * 4 / 5, steps = 30) else f.move(w / 2, hgt * 2 / 5, steps = 30) // angled: as EdgeContentsTest
            delay(250)
            assertNotNull("a finger holds the Contents panel", h.a.controller.contentsDrag.value)
            f.move(w * 4 / 5, f.y, steps = 12) // the panel follows the finger back, leaving most of the page in view
            delay(250)
            assertNotNull("a finger still holds the panel", h.a.controller.contentsDrag.value)
            h.sc.onActivity { a -> WindowCompat.getInsetsController(a.window, a.window.decorView).show(WindowInsetsCompat.Type.systemBars()) }
            h.awaitBars(true)
            delay(SETTLE_MS)
            assertSame("Contents swipe", hidden, h.layout())
        } finally { f.up() }
    }

    @Test fun gesture_navigation_a_top_swipe_moves_nothing() = run(GESTURAL) { topSwipe(it) }

    @Test fun button_navigation_a_top_swipe_moves_nothing() = run(THREE_BUTTON) { topSwipe(it) }

    @Test fun gesture_navigation_the_contents_swipe_moves_nothing() = run(GESTURAL) { contentsSwipe(it, angled = true) }

    @Test fun button_navigation_the_contents_swipe_moves_nothing() = run(THREE_BUTTON) { contentsSwipe(it, angled = false) }

    /** Scroll: the text runs down to the fixed bottom inset and never under the bar (C-099 keeps the top as it is). */
    @Test fun scroll_on_gesture_navigation_no_text_under_the_bar() = run(GESTURAL, Flow.SCROLLED) { topSwipe(it, Flow.SCROLLED) }

    private companion object {
        const val GESTURAL = "gestural"
        const val THREE_BUTTON = "threebutton"
        /** The bars' insets animation (about 300 ms) and the page's answer to any resize have ended. */
        const val SETTLE_MS = 800L
        /**
         * The extent (host CSS px, clipped to the viewport) of the text lines on screen in the loaded sections, with their
         * count, and the bottom of foliate's paged container (the text area in paged flow).
         */
        val TEXT_EXTENT = """(() => {
          const r = document.querySelector('foliate-view')?.renderer
          let top = Infinity, bottom = -Infinity, n = 0
          for (const c of r?.getContents?.() ?? []) {
            const doc = c.doc, f = doc?.defaultView?.frameElement?.getBoundingClientRect()
            if (!f || !doc.body) continue
            const walk = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT), range = doc.createRange()
            for (let t = walk.nextNode(); t; t = walk.nextNode()) {
              if (!t.nodeValue.trim()) continue
              range.selectNodeContents(t)
              for (const b of range.getClientRects()) {
                if (b.width <= 0 || b.height <= 0) continue
                const x = f.left + (b.left + b.right) / 2, y0 = f.top + b.top, y1 = f.top + b.bottom
                if (x < 0 || x >= innerWidth || y1 <= 0 || y0 >= innerHeight) continue
                top = Math.min(top, Math.max(0, y0)); bottom = Math.max(bottom, Math.min(innerHeight, y1)); n++
              }
            }
          }
          return JSON.stringify({ top, bottom, n, area: r?.containerRect?.bottom ?? -1, dpr: devicePixelRatio })
        })()"""
    }
}
