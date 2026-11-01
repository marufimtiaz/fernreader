package app.epubreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.epubreader.MainActivity
import app.epubreader.core.data.Flow
import app.epubreader.core.data.ReaderSettings
import app.epubreader.ui.Copy
import app.epubreader.ui.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F-031 (user report on the preview build): "Reading mode" is in the display panel, the Reader settings sheet and
 * Settings → Reading. All three edit the one stored `flow`, so a change in one shows in the others; in Scroll mode
 * the sheet's page animation is greyed out with "Applies to page mode".
 */
@RunWith(AndroidJUnit4::class)
class ReadingModeSyncTest {
    private val dev = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun obj(text: String): UiObject2 = checkNotNull(dev.wait(Until.findObject(By.text(text)), 5_000)) { "no '$text' on screen" }

    /** The segmented button whose label is [text]: the label is a child TextView of the checkable button node. */
    private fun button(text: String): UiObject2 {
        var o: UiObject2? = obj(text)
        while (o != null && !o.isCheckable) o = o.parent
        return checkNotNull(o) { "no button around '$text'" }
    }
    private fun selected(text: String): Boolean = button(text).isChecked
    private fun enabled(text: String): Boolean = button(text).isEnabled

    /**
     * Scrolls the display panel (the scroller holding "Text size", not the page behind it) until [text] is on screen:
     * slow drags in the middle of the panel, repeated (UiAutomator's scrollUntil stops after one drag on Compose).
     */
    private fun panelShow(text: String) {
        val size = checkNotNull(dev.wait(Until.findObject(By.text(Copy.textSize)), 3_000)) { "display panel not shown" }
        // the panel's scroller: the smallest scrollable around the "Text size" row (the page behind it is full-screen)
        val r = dev.findObjects(By.scrollable(true)).map { it.visibleBounds }
            .filter { it.contains(size.visibleBounds) && it.height() < dev.displayHeight - 50 }.minByOrNull { it.height() }
            ?: android.graphics.Rect(0, dev.displayHeight / 2, dev.displayWidth, dev.displayHeight)
        repeat(8) {
            if (dev.hasObject(By.text(text))) return
            dev.swipe(r.centerX(), r.top + r.height() * 3 / 4, r.centerX(), r.top + r.height() / 4, 40)
            dev.waitForIdle(300)
            // Compose sends no scroll event for the panel, so UiAutomation's node cache keeps the old rows (API 34+)
            if (android.os.Build.VERSION.SDK_INT >= 34) InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
        }
    }

    @Test fun panel_sheet_and_settings_edit_the_same_reading_mode(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.open(wp)

                // 1. Reader settings sheet: Reading mode above the animation; Scroll greys the animation out.
                sc.onActivity { it.controller.showSettings() }
                val mode = obj(Copy.layout); val anim = obj(Copy.pageAnimation)
                assertTrue("Reading mode is above Page animation", mode.visibleBounds.top < anim.visibleBounds.top)
                assertTrue(selected(Copy.flowPaged))
                assertTrue(enabled(Copy.animSlide))
                obj(Copy.flowScrolled).click()
                withTimeout(5_000) { h.a.controller.settings.first { it.flow == Flow.SCROLLED } }
                assertNotNull(dev.wait(Until.findObject(By.text(Copy.appliesToPageMode)), 5_000))
                assertFalse("animation must be disabled in Scroll mode", enabled(Copy.animSlide))
                sc.onActivity { it.controller.hideSettings() }
                delay(500)

                // 2. Display panel shows the sheet's change; a change there shows in the sheet.
                sc.onActivity { it.controller.showDisplay() }
                panelShow(Copy.flowScrolled)
                assertTrue("panel shows Scroll", selected(Copy.flowScrolled))
                obj(Copy.flowPaged).click()
                withTimeout(5_000) { h.a.controller.settings.first { it.flow == Flow.PAGED } }
                sc.onActivity { it.controller.hideDisplay() }
                delay(500)
                sc.onActivity { it.controller.showSettings() }
                assertTrue("sheet shows Pages", selected(Copy.flowPaged))
                assertTrue(enabled(Copy.animSlide))
                assertTrue(dev.findObject(By.text(Copy.appliesToPageMode)) == null)
                sc.onActivity { it.controller.hideSettings() }
                delay(500)

                // 3. Settings → Reading shows the value and changes it; the sheet and the panel follow.
                sc.onActivity { it.nav.push(Route.Settings) }
                val list = checkNotNull(dev.wait(Until.findObject(By.scrollable(true)), 10_000))
                val row = checkNotNull(list.scrollUntil(Direction.DOWN, Until.findObject(By.text(Copy.layout))))
                assertNotNull("row summary shows Pages", dev.findObject(By.text(Copy.flowPaged)))
                row.click()
                obj(Copy.flowAuto).click()
                withTimeout(5_000) { h.a.controller.settings.first { it.flow == Flow.AUTO } }
                sc.onActivity { it.goBack() }
                delay(800)
                sc.onActivity { it.controller.showSettings() }
                assertTrue("sheet shows Auto", selected(Copy.flowAuto))
                sc.onActivity { it.controller.hideSettings() }
                delay(500)
                sc.onActivity { it.controller.showDisplay() }
                panelShow(Copy.flowAuto)
                assertTrue("panel shows Auto", selected(Copy.flowAuto))
                sc.onActivity { it.controller.hideDisplay() }
                assertEquals(Flow.AUTO, h.a.controller.settings.value.flow)
            } finally {
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.stop()
            }
        }
    }
}
