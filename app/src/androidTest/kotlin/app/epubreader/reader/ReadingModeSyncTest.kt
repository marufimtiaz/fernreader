package app.epubreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.epubreader.MainActivity
import app.epubreader.settled
import app.epubreader.waitFresh
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
 * C-084: "Reading mode" is only in the reader's display panel (Aa): not in the Reader settings sheet, not in
 * Settings → Reading. Choosing Scroll in the panel still greys out the page animation ("Applies to page mode") in the
 * sheet and in Settings (F-031/F-032).
 */
@RunWith(AndroidJUnit4::class)
class ReadingModeSyncTest {
    private val dev = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Standing still: a click on a list that still moves can miss. */
    private fun obj(text: String): UiObject2 = checkNotNull(dev.settled(By.text(text), 5_000)) { "no '$text' on screen" }

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

    /** [text] on the Settings list, dragging down to the end then back up (explicit drags, fresh node cache). */
    private fun settingsFind(text: String): UiObject2? {
        dev.findObject(By.text(text))?.let { return it }
        val list = dev.wait(Until.findObject(By.scrollable(true)), 5_000) ?: return null
        val r = list.visibleBounds
        for (down in listOf(true, false)) repeat(8) {
            val (from, to) = if (down) r.top + r.height() * 3 / 4 to r.top + r.height() / 4 else r.top + r.height() / 4 to r.top + r.height() * 3 / 4
            dev.swipe(r.centerX(), from, r.centerX(), to, 30)
            dev.waitForIdle(300)
            if (android.os.Build.VERSION.SDK_INT >= 34) InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
            dev.findObject(By.text(text))?.let { return it }
        }
        return null
    }

    @Test fun reading_mode_is_only_in_the_display_panel_and_scroll_greys_the_animation(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.open(wp)

                // 1. Reader settings sheet: page turning starts with the animation, no Reading mode row.
                sc.onActivity { it.controller.showSettings() }
                obj(Copy.pageAnimation)
                assertTrue("no Reading mode in the sheet", dev.findObject(By.text(Copy.layout)) == null)
                assertTrue(dev.findObject(By.text(Copy.flowScrolled)) == null)
                assertTrue(enabled(Copy.animSlide))
                sc.onActivity { it.controller.hideSettings() }
                assertTrue("settings sheet closed", waitFresh(5_000) { !dev.hasObject(By.text(Copy.pageAnimation)) })

                // 2. Display panel: the one place; choose Scroll.
                sc.onActivity { it.controller.showDisplay() }
                panelShow(Copy.flowScrolled)
                assertNotNull(obj(Copy.layout))
                assertTrue("panel shows Pages", selected(Copy.flowPaged))
                obj(Copy.flowScrolled).click()
                withTimeout(5_000) { h.a.controller.settings.first { it.flow == Flow.SCROLLED } }
                sc.onActivity { it.controller.hideDisplay() }
                assertTrue("display panel closed", waitFresh(5_000) { !dev.hasObject(By.text(Copy.textSize)) })

                // 3. The sheet greys the animation out in Scroll mode.
                sc.onActivity { it.controller.showSettings() }
                assertNotNull(dev.wait(Until.findObject(By.text(Copy.appliesToPageMode)), 5_000))
                assertFalse("animation must be disabled in Scroll mode", enabled(Copy.animSlide))
                assertTrue(dev.findObject(By.text(Copy.layout)) == null)
                sc.onActivity { it.controller.hideSettings() }
                assertTrue("settings sheet closed", waitFresh(5_000) { !dev.hasObject(By.text(Copy.pageAnimation)) })

                // 4. Settings → Reading: no Reading mode row; the animation row says why it is off.
                sc.onActivity { it.nav.push(Route.Settings) }
                assertNotNull(dev.wait(Until.findObject(By.text(Copy.pageNumbers)), 10_000))
                assertNotNull(settingsFind(Copy.appliesToPageMode))
                assertTrue("no Reading mode in Settings", settingsFind(Copy.layout) == null)
                sc.onActivity { it.goBack() }
                assertTrue("back from Settings", waitFresh(5_000) { !dev.hasObject(By.text(Copy.pageNumbers)) })

                // 5. Back to Pages from the panel: the animation is on again.
                sc.onActivity { it.controller.showDisplay() }
                panelShow(Copy.flowPaged)
                obj(Copy.flowPaged).click()
                withTimeout(5_000) { h.a.controller.settings.first { it.flow == Flow.PAGED } }
                sc.onActivity { it.controller.hideDisplay() }
                assertTrue("display panel closed", waitFresh(5_000) { !dev.hasObject(By.text(Copy.textSize)) })
                sc.onActivity { it.controller.showSettings() }
                assertTrue(enabled(Copy.animSlide))
                assertTrue(dev.findObject(By.text(Copy.appliesToPageMode)) == null)
                sc.onActivity { it.controller.hideSettings() }
                assertEquals(Flow.PAGED, h.a.controller.settings.value.flow)
            } finally {
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.a.settingsStored()
                h.stop()
            }
        }
    }
}
