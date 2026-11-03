package app.epubreader.reader

import android.graphics.Color
import android.view.View
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epubreader.MainActivity
import app.epubreader.TestBooks
import app.epubreader.core.data.SourceRef
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.ui.Route
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P-002: stopped under the home screen, the WebView is INVISIBLE (still attached and laid out full size), so the first
 * frame of a hot start does not redraw it; it is VISIBLE again after that frame. On the reader route it never hides.
 * Not covered: the picker return (stopped under Home, book opened on the way back). The reveal one frame after the first frame
 * makes the WebView VISIBLE there too, so a test cannot tell whether the reader route's immediate VISIBLE did it.
 */
@RunWith(AndroidJUnit4::class)
class ReaderSurfaceVisibilityTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun webview_hides_only_while_stopped_under_the_home_screen(): Unit = runBlocking {
        val f = TestBooks.write(ctx, "vis.epub", TestBooks.epub(id = "urn:uuid:vis-${System.nanoTime()}"))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.reader.isReady.first { it } }
            fun visibility(): Int { var v = -1; sc.onActivity { v = it.reader.webView.visibility }; return v }
            suspend fun awaitVisibility(want: Int) = withTimeout(5_000) { while (visibility() != want) delay(20) }
            fun assertLaidOutFullSize() = sc.onActivity {
                val w = it.reader.webView
                assertTrue("attached", w.isAttachedToWindow)
                assertTrue("laid out ${w.width}x${w.height}", w.width > 0 && w.height > it.window.decorView.height / 2)
            }

            awaitVisibility(View.VISIBLE)
            sc.moveToState(Lifecycle.State.CREATED) // HOME: stopped
            assertEquals(View.INVISIBLE, visibility()); assertLaidOutFullSize()
            sc.moveToState(Lifecycle.State.RESUMED) // hot start
            awaitVisibility(View.VISIBLE); assertLaidOutFullSize()

            val relocated = async(start = CoroutineStart.UNDISPATCHED) { a.reader.events.filterIsInstance<FromWeb.Relocate>().first() }
            sc.onActivity { it.openBook(SourceRef.File(f.path)) }
            withTimeout(10_000) { relocated.await() }
            sc.moveToState(Lifecycle.State.CREATED)
            assertEquals(View.VISIBLE, visibility()) // the reader page is what the user returns to
            sc.moveToState(Lifecycle.State.RESUMED)
            sc.onActivity { it.goBack(); assertNotEquals(Route.Reader, it.nav.top) }
            assertEquals(View.VISIBLE, visibility())

        }
    }

    /**
     * C-089 (amended): covered by the library, the WebView stays VISIBLE (the page keeps running) but is not drawn
     * (alpha 0); after an open from the library it stays undrawn under the reader's opaque loading screen and is drawn
     * in every frame once the page has painted (loading over), and the page shows text then (no blank frame). Back to
     * the library: not drawn again.
     */
    @Test fun covered_webview_is_not_drawn_and_the_painted_page_replaces_the_loading_screen(): Unit = runBlocking {
        val f = TestBooks.write(ctx, "vis2.epub", TestBooks.epub(id = "urn:uuid:vis2-${System.nanoTime()}"))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.reader.isReady.first { it } }
            fun alpha(): Float { var v = -1f; sc.onActivity { v = it.reader.webView.alpha }; return v }
            sc.onActivity { assertNotEquals(Route.Reader, it.nav.top); assertEquals(View.VISIBLE, it.reader.webView.visibility) }
            withTimeout(5_000) { while (alpha() != 0f) delay(20) }

            // At each draw of the window from the open on: the WebView's alpha and whether the reader was loading.
            val drawn = CopyOnWriteArrayList<Pair<Float, Boolean>>()
            lateinit var l: ViewTreeObserver.OnDrawListener
            sc.onActivity {
                val w = it.reader.webView
                l = ViewTreeObserver.OnDrawListener { drawn.add(w.alpha to a.controller.state.value.loading) }
                w.viewTreeObserver.addOnDrawListener(l)
                it.openBook(SourceRef.File(f.path))
            }
            withTimeout(10_000) { while (drawn.isEmpty()) delay(5) }
            assertEquals("the first reader frame is the loading screen, the page is not drawn under it", 0f to true, drawn.first())
            withTimeout(15_000) { a.controller.state.first { !it.loading && it.error == null } }
            delay(300)
            sc.onActivity { it.reader.webView.viewTreeObserver.removeOnDrawListener(l) }
            assertTrue("the page is drawn in every frame once loaded: $drawn", drawn.all { (alpha, loading) -> loading || alpha == 1f })
            assertTrue("the page was drawn: $drawn", drawn.last().first == 1f)
            val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            var ink = 0; var n = 0
            for (y in shot.height / 5 until shot.height * 4 / 5 step 4) for (x in shot.width / 10 until shot.width * 9 / 10 step 4) {
                val c = shot.getPixel(x, y); n++
                if ((Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 < 128) ink++
            }
            assertTrue("the page shows text after loading (ink $ink of $n)", ink > n / 500)

            sc.onActivity { it.goBack(); assertNotEquals(Route.Reader, it.nav.top) }
            withTimeout(5_000) { while (alpha() != 0f) delay(20) }
            sc.onActivity { assertEquals(View.VISIBLE, it.reader.webView.visibility) }
        }
    }
}
