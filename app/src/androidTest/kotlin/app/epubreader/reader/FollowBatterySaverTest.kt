package app.epubreader.reader

import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epubreader.MainActivity
import app.epubreader.TestBooks
import app.epubreader.core.data.PageAnimation
import app.epubreader.core.data.PageNumbers
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.ReadingThemeId
import app.epubreader.reader.bridge.FromWeb
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * C-107 "Follow Battery Saver" (on by default): with a book open, turning the phone's power save mode on (`cmd power
 * set-mode 1`) makes the page true Black, page turns lose their animation, the screen-page counter stops (the indicator
 * shows a percentage) and the window asks for the panel's lowest refresh rate; turning it off brings all four back, the
 * saved settings untouched. With the setting off nothing changes. Power save mode is off after each test.
 */
@RunWith(AndroidJUnit4::class)
class FollowBatterySaverTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun shell(cmd: String): String {
        val fd = inst.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }.trim()
    }
    private fun powerSave(on: Boolean) {
        shell("cmd power set-mode ${if (on) 1 else 0}")
        val pm = inst.targetContext.getSystemService(PowerManager::class.java)
        val end = System.currentTimeMillis() + 5_000
        while (pm.isPowerSaveMode != on && System.currentTimeMillis() < end) Thread.sleep(50)
        assertEquals("the phone's power save mode", on, pm.isPowerSaveMode)
    }

    @Before fun powerSaveOff() = powerSave(false)
    @After fun powerSaveOffAfter() = powerSave(false)

    private fun <T> ActivityScenario<MainActivity>.read(f: (MainActivity) -> T): T { var r: T? = null; onActivity { r = f(it) }; @Suppress("UNCHECKED_CAST") return r as T }

    /** What the reader shows: the page's background, whether turns slide, the refresh request, the indicator's percent. */
    private data class Look(val background: String, val animated: Boolean, val refresh: RefreshRate, val percent: Boolean)
    private suspend fun ActivityScenario<MainActivity>.look(): Look {
        val bg = JSONTokener(js("getComputedStyle(document.body).backgroundColor")).nextValue() as String
        val animated = js("(() => { const r = document.querySelector('foliate-view')?.renderer; return r ? r.hasAttribute('animated') : null })()")
        return Look(bg, animated == "true", read { it.requestedRefresh }, read { it.controller.shownPage.value.percent != null })
    }
    private suspend fun ActivityScenario<MainActivity>.shows(what: String, want: Look) {
        val end = System.currentTimeMillis() + 5_000
        while (look() != want && System.currentTimeMillis() < end) delay(100)
        assertEquals(what, want, look())
    }

    /** Page counts (non-empty) the web side's screen-page counter sent since the harness started watching. */
    private val counted = AtomicInteger()
    private fun ReaderHarness.watchCounts(scope: kotlinx.coroutines.CoroutineScope): Job =
        scope.launch(Job(), start = CoroutineStart.UNDISPATCHED) { a.reader.events.collect { if (it is FromWeb.PageCounts && it.counts.isNotEmpty()) counted.incrementAndGet() } }

    /** A new layout (one text-size step), as the counter would count it. */
    private fun ActivityScenario<MainActivity>.relayout(step: Int) = onActivity { it.controller.updateSettings { s -> s.stepFont(step) } }

    private fun withBook(s: ReaderSettings, body: suspend (ActivityScenario<MainActivity>, ReaderHarness) -> Unit) = runBlocking {
        val f = TestBooks.write(inst.targetContext, "saver.epub", TestBooks.epub(id = "urn:uuid:saver-${System.nanoTime()}", chapters = 6, paragraphs = 30))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            val watch = h.watchCounts(this)
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { _ -> s } }
                h.open(f)
                body(sc, h)
            } finally {
                watch.cancel()
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.a.settingsStored()
                h.stop()
            }
        }
    }

    private val chosen = ReaderSettings(theme = ReadingThemeId.WARM, pageAnimation = PageAnimation.SLIDE, pageNumbers = PageNumbers.SCREEN_BOOK)
    private val normal = Look(WARM_CSS, animated = true, RefreshRate.READING, percent = false)

    @Test fun battery_saver_switches_theme_animation_counter_and_refresh_live() = withBook(chosen) { sc, h ->
        sc.shows("before", normal)
        withTimeout(15_000) { while (counted.get() == 0) delay(50) } // the counter runs on screen pages
        val saved = sc.read { it.controller.settings.value }

        powerSave(true)
        sc.shows("Battery Saver on", Look(BLACK_CSS, animated = false, RefreshRate.LOWEST, percent = true))
        assertTrue(Regex("\\d+%").matches(sc.read { app.epubreader.ui.Copy.pageLabelShort(it.controller.shownPage.value) } ?: ""))
        // A new layout is not counted while Battery Saver is on.
        sc.relayout(+1); h.quiet()
        counted.set(0)
        delay(3_000) // the window in which no count may arrive: fixed
        assertEquals("page counts under Battery Saver", 0, counted.get())
        // The saved settings are the reader's own, in memory and in storage.
        sc.read { it }.settingsStored()
        val now = sc.read { it.controller.settings.value }
        assertEquals(Triple(saved.theme, saved.pageAnimation, saved.pageNumbers), Triple(now.theme, now.pageAnimation, now.pageNumbers))

        powerSave(false)
        sc.shows("Battery Saver off", normal)
        withTimeout(15_000) { while (counted.get() == 0) delay(50) } // counting resumes for the current layout
    }

    @Test fun with_the_setting_off_battery_saver_changes_nothing() = withBook(chosen.copy(followBatterySaver = false)) { sc, h ->
        sc.shows("before", normal)
        powerSave(true)
        delay(1_500) // the window in which nothing may change: fixed
        sc.shows("Battery Saver on, setting off", normal)
        counted.set(0)
        sc.relayout(+1); h.quiet()
        withTimeout(15_000) { while (counted.get() == 0) delay(50) } // the counter still runs
        assertNull(sc.read { it.controller.shownPage.value.percent })
    }

    private companion object {
        // ReadingTheme.Warm / Black backgrounds, as SystemThemeTest.
        const val WARM_CSS = "rgb(244, 233, 211)"
        const val BLACK_CSS = "rgb(0, 0, 0)"
    }
}
