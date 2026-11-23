package app.fernreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.fernreader.MainActivity
import app.fernreader.core.data.Flow
import app.fernreader.core.data.ReaderSettings
import app.fernreader.reader.bridge.ToWeb
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** F-037 / P-022: rotating twice brings back the same passage (War and Peace). */
@RunWith(AndroidJUnit4::class)
class RotationTest {
    private val dev = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @After fun upright() { dev.setOrientationNatural(); dev.unfreezeRotation() }

    private suspend fun ReaderHarness.rotateAndSettle(left: Boolean): app.fernreader.reader.bridge.FromWeb.Relocate {
        fun width(): Int { var w = 0; sc.onActivity { w = it.reader.webView.width }; return w }
        val before = width()
        if (left) dev.setOrientationLeft() else dev.setOrientationNatural()
        withTimeout(10_000) { while (width() == before) delay(20) } // the window resized
        return quiet() // the page is laid out again and re-anchored
    }

    private fun rotateTwiceKeepsCfi(flow: Flow) = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready(); h.open(wp)
                sc.onActivity { it.controller.updateSettings { s -> s.copy(flow = flow) } }
                h.still()
                h.tocJump("BOOK ONE: 1805")
                repeat(4) { h.send(ToWeb.Next()); h.settle() }
                val start = h.settle()
                val land = h.rotateAndSettle(left = true)
                assertEquals("scrolled in landscape only with AUTO", flow == Flow.AUTO, land.pages == 0)
                h.rotateAndSettle(left = false)
                val back = h.settle()
                assertEquals(start.sectionIndex, back.sectionIndex)
                assertEquals(start.cfi, back.cfi)
            } finally { sc.onActivity { it.controller.updateSettings { ReaderSettings() } }; h.a.settingsStored(); h.stop() }
        }
    }

    @Test fun paged_rotate_twice_same_cfi() = rotateTwiceKeepsCfi(Flow.PAGED)

    /** AUTO: landscape is scrolled (C-004); back in portrait the same page. */
    @Test fun auto_rotate_twice_same_cfi() = rotateTwiceKeepsCfi(Flow.AUTO)
}
