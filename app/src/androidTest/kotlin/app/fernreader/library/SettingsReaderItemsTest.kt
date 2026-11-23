package app.fernreader.library

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.fernreader.EpubReaderApp
import app.fernreader.MainActivity
import app.fernreader.core.data.ReaderSettings
import app.fernreader.reader.settingsStored
import app.fernreader.settled
import app.fernreader.ui.Copy
import app.fernreader.ui.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** F-100 (M6 after M7): the reader's settings are on the Settings screen and change the same ReaderSettings, stored. */
@RunWith(AndroidJUnit4::class)
class SettingsReaderItemsTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(inst)
    private val graph get() = (inst.targetContext.applicationContext as EpubReaderApp).graph

    /**
     * [text] on the settings list, scrolling down then up with explicit drags (scrollUntil stops after one drag on
     * Compose) and refreshing UiAutomation's node cache after each (Compose sends no scroll event for the list, API 34+).
     */
    private fun find(text: String): UiObject2? {
        device.findObject(By.text(text))?.let { return it }
        val list = device.wait(Until.findObject(By.scrollable(true)), 5_000) ?: return null
        val r = list.visibleBounds
        for (down in listOf(true, false)) repeat(8) {
            val (from, to) = if (down) r.top + r.height() * 3 / 4 to r.top + r.height() / 4 else r.top + r.height() / 4 to r.top + r.height() * 3 / 4
            device.swipe(r.centerX(), from, r.centerX(), to, 30)
            device.waitForIdle(300)
            if (android.os.Build.VERSION.SDK_INT >= 34) inst.uiAutomation.clearCache()
            if (device.hasObject(By.text(text))) return device.settled(By.text(text), 3_000) // a click on the moving list can miss
        }
        return null
    }

    private suspend fun stored(): ReaderSettings = ReaderSettings.decode(graph.settings.get(ReaderSettings.KEY))

    @Test fun reader_items_are_listed_and_change_reader_settings(): Unit = runBlocking {
        inst.uiAutomation.executeShellCommand("appops set ${inst.targetContext.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            a.settingsStored() // its load would replace the reset below
            sc.onActivity { it.controller.updateSettings { ReaderSettings() }; it.nav.push(Route.Settings) }
            try {
                assertNotNull(device.wait(Until.findObject(By.text(Copy.pageNumbers)), 10_000))
                for (t in listOf(Copy.pageAnimation, Copy.swipeDirection, Copy.volumeKeys, Copy.fullscreen, Copy.keepScreenOn, Copy.orientation, Copy.confirmLinks)) {
                    assertNotNull(t, find(t))
                }
                assertNotNull(Copy.volumeSummary(app.fernreader.core.data.VolumeKeys.OFF), find(Copy.volumeSummary(app.fernreader.core.data.VolumeKeys.OFF)))
                val tap = checkNotNull(find(Copy.tapZones))
                assertEquals(false, a.controller.settings.value.tapZones)
                tap.click()
                withTimeout(5_000) { a.controller.settings.first { it.tapZones } }
                withTimeout(5_000) { while (!stored().tapZones) delay(100) } // persisted (after the 300 ms debounce)
                checkNotNull(find(Copy.tapZones)).click()
                withTimeout(5_000) { a.controller.settings.first { !it.tapZones } }
                withTimeout(5_000) { while (stored().tapZones) delay(100) }
            } finally {
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                a.settingsStored()
            }
        }
    }
}
