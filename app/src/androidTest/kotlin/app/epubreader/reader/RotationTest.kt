package app.epubreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.epubreader.MainActivity
import app.epubreader.core.data.Flow
import app.epubreader.core.data.ReaderSettings
import app.epubreader.reader.bridge.ToWeb
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** F-037 / P-022: rotating twice brings back the same passage (War and Peace). */
@RunWith(AndroidJUnit4::class)
class RotationTest {
    private val dev = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @After fun upright() { dev.setOrientationNatural(); dev.unfreezeRotation() }

    private suspend fun ReaderHarness.rotateAndSettle(left: Boolean): app.epubreader.reader.bridge.FromWeb.Relocate {
        if (left) dev.setOrientationLeft() else dev.setOrientationNatural()
        delay(2_500) // the window resizes, the page is laid out again and re-anchored
        drain(); return settle()
    }

    private fun rotateTwiceKeepsCfi(flow: Flow) = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready(); h.open(wp)
                sc.onActivity { it.controller.updateSettings { s -> s.copy(flow = flow) } }
                delay(500)
                h.tocJump("BOOK ONE: 1805")
                repeat(4) { h.send(ToWeb.Next()); h.settle() }
                val start = h.settle()
                val land = h.rotateAndSettle(left = true)
                assertEquals("scrolled in landscape only with AUTO", flow == Flow.AUTO, land.pages == 0)
                h.rotateAndSettle(left = false)
                val back = h.settle()
                assertEquals(start.sectionIndex, back.sectionIndex)
                assertEquals(start.cfi, back.cfi)
            } finally { sc.onActivity { it.controller.updateSettings { ReaderSettings() } }; h.stop() }
        }
    }

    @Test fun paged_rotate_twice_same_cfi() = rotateTwiceKeepsCfi(Flow.PAGED)

    /** AUTO: landscape is scrolled (C-004); back in portrait the same page. */
    @Test fun auto_rotate_twice_same_cfi() = rotateTwiceKeepsCfi(Flow.AUTO)
}
