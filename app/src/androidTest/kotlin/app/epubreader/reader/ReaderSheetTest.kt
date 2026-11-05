package app.epubreader.reader

import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.epubreader.MainActivity
import app.epubreader.bench.BenchCommands
import app.epubreader.bench.Probe
import app.epubreader.core.data.OrientationLock
import app.epubreader.core.data.PageAnimation
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.ReaderSheetTab
import app.epubreader.settled
import app.epubreader.ui.Copy
import app.epubreader.waitFresh
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * C-095: one icon in the bottom bar opens one sheet with the tabs Display and General, switched by a tap or a sideways
 * swipe; the sheet opens on the last tab, also in a new activity; the controls change the reader live; sliders keep
 * their drags; Reading mode is only in Display (C-084); the page stays visible above the sheet.
 */
@RunWith(AndroidJUnit4::class)
class ReaderSheetTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)

    private fun obj(text: String): UiObject2 = checkNotNull(dev.settled(By.text(text), 5_000)) { "no '$text' on screen" }
    private fun desc(text: String): UiObject2 = checkNotNull(dev.settled(By.desc(text), 5_000)) { "no '$text' on screen" }
    private fun shown(text: String) = assertTrue("'$text' on screen", waitFresh(5_000) { dev.hasObject(By.text(text)) })
    private fun gone(text: String) = assertTrue("'$text' gone", waitFresh(5_000) { !dev.hasObject(By.text(text)) })

    private fun displayTabShown() { shown(Copy.textSize); gone(Copy.pageAnimation) }
    private fun generalTabShown() { shown(Copy.pageAnimation); gone(Copy.textSize) }

    private suspend fun openBook(h: ReaderHarness, change: (ReaderSettings) -> ReaderSettings = { it }) {
        h.ready()
        h.sc.onActivity { it.controller.updateSettings { change(ReaderSettings()) } }
        h.open(warAndPeace())
    }

    /** The controls, then a click on the one icon: the only way a reader gets in. */
    private suspend fun openFromBar(h: ReaderHarness) {
        if (!h.a.controller.state.value.chromeVisible) dev.click(dev.displayWidth / 2, dev.displayHeight / 2)
        withTimeout(10_000) { h.a.controller.state.first { it.chromeVisible } }
        desc(Copy.readerSettings).click()
        withTimeout(5_000) { h.a.controller.state.first { it.sheetVisible } }
    }

    private fun closeSheet(h: ReaderHarness) {
        h.sc.onActivity { it.controller.hideSheet() }
        gone(Copy.general)
    }

    /** A fast sideways swipe along the row of [label], well inside the screen: not on a slider. */
    private fun swipe(label: String, toGeneral: Boolean) {
        val y = obj(label).visibleBounds.centerY()
        val (left, right) = (dev.displayWidth * 0.2).toInt() to (dev.displayWidth * 0.8).toInt()
        if (toGeneral) dev.swipe(right, y, left, y, 12) else dev.swipe(left, y, right, y, 12)
    }

    /** A slow drag over the node's width from one end to the other; slow so that a fling plays no part. */
    private fun dragAcross(o: UiObject2, leftwards: Boolean) {
        val b = o.visibleBounds
        val (from, to) = if (leftwards) b.right - b.width() / 10 to b.left + b.width() / 10 else b.left + b.width() / 10 to b.right - b.width() / 10
        dev.swipe(from, b.centerY(), to, b.centerY(), 60)
    }

    /** Scrolls the open tab until [text] is on screen (UiAutomator's scrollUntil stops after one drag on Compose). */
    private fun sheetShow(text: String): UiObject2 {
        val top = obj(Copy.general).visibleBounds.bottom
        repeat(10) {
            if (waitFresh(300) { dev.hasObject(By.text(text)) }) return obj(text)
            val x = dev.displayWidth / 8 // the label column: no slider grabs a vertical drag, but stay off the switches
            dev.swipe(x, dev.displayHeight - (dev.displayHeight - top) / 4, x, top + (dev.displayHeight - top) / 4, 40)
        }
        return obj(text)
    }

    private fun shot(name: String) {
        dev.takeScreenshot(File(inst.targetContext.getExternalFilesDir(null), "$name.png"))
    }

    @Test fun one_icon_opens_one_sheet_and_the_page_stays_visible_above_it(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                openBook(h)
                dev.click(dev.displayWidth / 2, dev.displayHeight / 2)
                withTimeout(10_000) { h.a.controller.state.first { it.chromeVisible } }
                assertEquals("one settings icon", 1, dev.wait(Until.findObjects(By.desc(Copy.readerSettings)), 5_000).size)
                assertNull("the second icon is gone", dev.findObject(By.desc(Copy.display)))
                openFromBar(h)
                assertNotNull(obj(Copy.display)); assertNotNull(obj(Copy.general))
                displayTabShown()
                assertFalse("the controls gave way to the sheet", h.a.controller.state.value.chromeVisible)
                shot("c095-display")
                // The sheet is no taller than the display panel was (460 dp over the navigation bar), tabs included.
                var nav = 0
                sc.onActivity { nav = if (Build.VERSION.SDK_INT >= 30) it.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom else 0 }
                val density = inst.targetContext.resources.displayMetrics.density
                val height = (dev.displayHeight - nav - obj(Copy.general).visibleBounds.top) / density
                assertTrue("sheet height $height dp", height <= 462)
                assertTrue("a sheet, not a screen", obj(Copy.general).visibleBounds.top > dev.displayHeight / 3)
                dev.pressBack()
                gone(Copy.general)
                assertFalse(h.a.controller.state.value.sheetVisible)
            } finally {
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.a.settingsStored(); h.stop()
            }
        }
    }

    @Test fun tabs_switch_by_tap_and_by_swipe_and_reading_mode_is_only_in_display(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                openBook(h)
                openFromBar(h)
                displayTabShown()

                obj(Copy.general).click()
                generalTabShown()
                assertEquals(ReaderSheetTab.GENERAL, h.a.controller.settings.value.sheetTab)
                shot("c095-general")
                obj(Copy.display).click()
                displayTabShown()
                assertEquals(ReaderSheetTab.DISPLAY, h.a.controller.settings.value.sheetTab)

                swipe(Copy.font, toGeneral = true)
                generalTabShown()
                withTimeout(5_000) { h.a.controller.settings.first { it.sheetTab == ReaderSheetTab.GENERAL } }
                swipe(Copy.pageAnimation, toGeneral = false)
                displayTabShown()
                withTimeout(5_000) { h.a.controller.settings.first { it.sheetTab == ReaderSheetTab.DISPLAY } }

                // C-084: scrolled from top to bottom, General never shows Reading mode; Display does.
                obj(Copy.general).click()
                generalTabShown()
                repeat(6) {
                    assertNull("no Reading mode in General", dev.findObject(By.text(Copy.layout)))
                    assertNull(dev.findObject(By.text(Copy.flowScrolled)))
                    val x = dev.displayWidth / 8
                    dev.swipe(x, dev.displayHeight * 9 / 10, x, dev.displayHeight * 7 / 10, 40)
                    waitFresh(200) { false }
                }
                obj(Copy.display).click()
                sheetShow(Copy.layout)
                assertNotNull(obj(Copy.flowScrolled))
            } finally {
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.a.settingsStored(); h.stop()
            }
        }
    }

    @Test fun a_drag_on_a_slider_or_the_font_row_is_not_a_tab_swipe(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                openBook(h) { it.copy(lineHeight = 2.0, marginPercent = 0) }
                openFromBar(h)
                displayTabShown()
                val c = h.a.controller
                // Leftwards is General's way, rightwards the way past the first tab: neither moves the sheet.
                dragAcross(desc(Copy.lineSpacing), leftwards = true)
                withTimeout(5_000) { c.settings.first { it.lineHeight < 1.5 } }
                dragAcross(desc(Copy.margins), leftwards = false)
                withTimeout(5_000) { c.settings.first { it.marginPercent > 7 } }
                dragAcross(desc(Copy.margins), leftwards = true)
                withTimeout(5_000) { c.settings.first { it.marginPercent < 3 } }
                displayTabShown()
                assertEquals(ReaderSheetTab.DISPLAY, c.settings.value.sheetTab)

                // The font row scrolls sideways; at its end the drag stays with it too.
                val chip = obj(Copy.fontName(ReaderSettings.LITERATA)).visibleBounds
                repeat(2) { dev.swipe(dev.displayWidth * 9 / 10, chip.centerY(), dev.displayWidth / 10, chip.centerY(), 12) }
                displayTabShown()

                // A greyed-out slider (Brightness follows the system by default) doesn't hand its drag to the tabs.
                sheetShow(Copy.followSystem)
                assertNull(c.settings.value.brightness)
                dragAcross(desc(Copy.brightness), leftwards = true)
                waitFresh(500) { false }
                shown(Copy.followSystem)
                assertEquals(ReaderSheetTab.DISPLAY, c.settings.value.sheetTab)
                assertNull("still following the system", c.settings.value.brightness)
            } finally {
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.a.settingsStored(); h.stop()
            }
        }
    }

    @Test fun controls_of_both_tabs_change_the_reader_live(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                openBook(h)
                h.quiet()
                openFromBar(h)
                h.drain()
                desc(Copy.largerText).click()
                h.painted() // the page behind the open sheet was laid out again
                assertEquals(1.1, h.a.controller.settings.value.fontScale, 0.001)
                shown(Copy.textSize)

                obj(Copy.general).click()
                generalTabShown()
                obj(Copy.animNone).click()
                withTimeout(5_000) { h.a.controller.settings.first { it.pageAnimation == PageAnimation.NONE } }
                // Keep screen on acts on the window at once, with the sheet still open.
                val keepOn = sheetShow(Copy.keepScreenOn).visibleBounds.centerY()
                fun windowKeepsOn(): Boolean { var on = false; sc.onActivity { on = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 }; return on }
                assertTrue(windowKeepsOn())
                dev.findObjects(By.checkable(true)).first { it.visibleBounds.run { top <= keepOn && bottom >= keepOn } }.click()
                withTimeout(5_000) { h.a.controller.settings.first { !it.keepScreenOn } }
                assertTrue("the window follows", waitFresh(5_000) { !windowKeepsOn() })
                shown(Copy.keepScreenOn)
            } finally {
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.a.settingsStored(); h.stop()
            }
        }
    }

    @Test fun the_sheet_opens_on_the_last_tab_also_in_a_new_activity_and_display_command_opens_display(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                openBook(h)
                openFromBar(h)
                displayTabShown() // the default
                obj(Copy.general).click()
                generalTabShown()
                dev.pressBack()
                gone(Copy.general)
                openFromBar(h)
                generalTabShown()
                closeSheet(h)
                h.a.settingsStored()
            } finally {
                h.stop()
            }
        }
        Probe.enabled = true // the activity registers the bench commands only then
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                assertEquals("read back from storage", ReaderSheetTab.GENERAL, h.a.controller.settings.value.sheetTab)
                h.open(warAndPeace())
                openFromBar(h)
                generalTabShown()

                // bench/groups/action.sh, P-020: `display` shows the Display tab, whether the sheet is open or not, and
                // `hideDisplay` closes the sheet.
                sc.onActivity { BenchCommands.dispatch("display", "") }
                displayTabShown()
                sc.onActivity { BenchCommands.dispatch("hideDisplay", "") }
                gone(Copy.general)
                sc.onActivity { it.controller.selectSheetTab(ReaderSheetTab.GENERAL) }
                sc.onActivity { BenchCommands.dispatch("display", "") }
                displayTabShown()
                sc.onActivity { BenchCommands.dispatch("hideDisplay", "") }
                gone(Copy.general)
            } finally {
                Probe.enabled = false
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.a.settingsStored(); h.stop()
            }
        }
    }

    @Test fun in_landscape_the_tabs_stay_in_reach(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                openBook(h) { it.copy(orientation = OrientationLock.LANDSCAPE) }
                assertTrue("landscape", waitFresh(10_000) { dev.displayWidth > dev.displayHeight })
                openFromBar(h)
                displayTabShown()
                shot("c095-landscape")
                var top = 0
                sc.onActivity { top = it.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.statusBars()).top }
                assertTrue("tabs below the status bar", obj(Copy.general).visibleBounds.top >= top)
                obj(Copy.general).click()
                generalTabShown()
                swipe(Copy.pageAnimation, toGeneral = false)
                displayTabShown()
                withTimeout(5_000) { h.a.controller.settings.first { it.sheetTab == ReaderSheetTab.DISPLAY } } // when the swipe has settled
            } finally {
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.a.settingsStored(); h.stop()
            }
        }
    }
}
