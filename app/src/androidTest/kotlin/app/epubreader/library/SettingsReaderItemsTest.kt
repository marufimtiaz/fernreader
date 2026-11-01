package app.epubreader.library

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.epubreader.EpubReaderApp
import app.epubreader.MainActivity
import app.epubreader.core.data.ReaderSettings
import app.epubreader.ui.Copy
import app.epubreader.ui.Route
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

    private fun find(text: String): UiObject2? {
        device.findObject(By.text(text))?.let { return it }
        val list = device.wait(Until.findObject(By.scrollable(true)), 5_000) ?: return null
        return list.scrollUntil(Direction.DOWN, Until.findObject(By.text(text)))
            ?: list.scrollUntil(Direction.UP, Until.findObject(By.text(text)))
    }

    private suspend fun stored(): ReaderSettings = ReaderSettings.decode(graph.settings.get(ReaderSettings.KEY))

    @Test fun reader_items_are_listed_and_change_reader_settings(): Unit = runBlocking {
        inst.uiAutomation.executeShellCommand("appops set ${inst.targetContext.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            delay(1_000) // the controller has read the stored settings (its load would replace the reset below)
            sc.onActivity { a = it; it.controller.updateSettings { ReaderSettings() }; it.nav.push(Route.Settings) }
            try {
                assertNotNull(device.wait(Until.findObject(By.text(Copy.pageNumbers)), 10_000))
                for (t in listOf(Copy.pageAnimation, Copy.swipeDirection, Copy.volumeKeys, Copy.fullscreen, Copy.keepScreenOn, Copy.orientation, Copy.confirmLinks)) {
                    assertNotNull(t, find(t))
                }
                assertNotNull(Copy.volumeSummary(app.epubreader.core.data.VolumeKeys.OFF), find(Copy.volumeSummary(app.epubreader.core.data.VolumeKeys.OFF)))
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
                delay(500)
            }
        }
    }
}
