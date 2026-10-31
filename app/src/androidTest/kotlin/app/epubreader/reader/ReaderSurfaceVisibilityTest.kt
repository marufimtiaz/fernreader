package app.epubreader.reader

import android.view.View
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P-002: stopped under the home screen, the WebView is INVISIBLE (still attached and laid out full size), so the first
 * frame of a hot start does not redraw it; it is VISIBLE again after that frame. On the reader route it never hides.
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
            sc.onActivity { it.goBack(); assertEquals(Route.Home, it.nav.top) }
            assertEquals(View.VISIBLE, visibility())
        }
    }
}
