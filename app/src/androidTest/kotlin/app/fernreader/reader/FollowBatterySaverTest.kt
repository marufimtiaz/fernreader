package app.fernreader.reader

import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import app.fernreader.MainActivity
import app.fernreader.TestBooks
import app.fernreader.core.data.PageAnimation
import app.fernreader.core.data.PageNumbers
import app.fernreader.core.data.ReaderSettings
import app.fernreader.core.data.ReaderSheetTab
import app.fernreader.core.data.ReadingThemeId
import app.fernreader.reader.bridge.FromWeb
import app.fernreader.settled
import app.fernreader.ui.Copy
import app.fernreader.ui.Route
import app.fernreader.waitFresh
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * saved settings untouched. With the setting off nothing changes. Power save mode is off, and the battery back to the
 * device's own state, after each test.
 *
 * C-116: while Battery Saver applies, the settings it overrides (theme, page-turn animation, page numbers) are greyed
 * out, in the reader's sheet and on the Settings screen, with the saved choice still marked and a line saying why; they
 * are enabled again at once when it stops applying.
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

    // The system keeps Battery Saver off while the phone charges (the emulator is always on AC): unplugged for the test.
    @Before fun unplugged() { shell("dumpsys battery unplug"); powerSave(false) }
    @After fun powerSaveOffAndPluggedIn() { powerSave(false); shell("dumpsys battery reset") }

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
        assertTrue(Regex("\\d+%").matches(sc.read { app.fernreader.ui.Copy.pageLabelShort(it.controller.shownPage.value) } ?: ""))
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

    private val dev = UiDevice.getInstance(inst)

    private fun node(text: String, how: (String) -> BySelector = By::textContains): UiObject2 =
        checkNotNull(dev.settled(how(text), 5_000)) { "no '$text' on screen" }
    /**
     * The choice or row labelled [text]. Compose shows the label as a child of the clickable node: a choice's is the
     * nearest checkable one above it (a segmented button wraps its label once more), a settings row's its parent.
     */
    private fun choice(text: String): UiObject2 {
        val label = node(text, By::text)
        generateSequence(label.parent) { it.parent }.take(3).firstOrNull { it.isCheckable }?.let { return it }
        return checkNotNull(label.parent) { "'$text' has no parent" }
    }
    private fun enabled(text: String, want: Boolean) {
        val ok = waitFresh(5_000) { runCatching { choice(text).isEnabled == want }.getOrDefault(false) }
        assertTrue("'$text' enabled = $want", ok)
    }
    private fun enabledDesc(desc: String, want: Boolean) =
        assertTrue("'$desc' enabled = $want", waitFresh(5_000) { runCatching { dev.findObject(By.desc(desc))?.isEnabled == want }.getOrDefault(false) })
    private fun shown(text: String, want: Boolean) =
        assertTrue("'$text' shown = $want", waitFresh(5_000) { dev.hasObject(By.textContains(text)) == want })

    /** Scrolls the sheet's open tab, downwards or back up, until [text] is on screen (as ReaderSheetTest); exact, as the page has text too. */
    private fun sheetShow(text: String, down: Boolean = true): UiObject2 {
        val top = node(Copy.general).visibleBounds.bottom
        val x = dev.displayWidth / 8 // the label column: off the sliders and switches
        val (low, high) = dev.displayHeight - (dev.displayHeight - top) / 4 to top + (dev.displayHeight - top) / 4
        repeat(10) {
            if (waitFresh(300) { dev.hasObject(By.text(text)) }) return node(text, By::text)
            if (down) dev.swipe(x, low, x, high, 40) else dev.swipe(x, high, x, low, 40)
        }
        return node(text, By::text)
    }

    /** [text] on the Settings list, scrolled to (as SettingsReaderItemsTest). */
    private fun settingsShow(text: String): UiObject2 {
        if (dev.hasObject(By.text(text))) return node(text, By::text)
        val r = checkNotNull(dev.settled(By.scrollable(true), 5_000)).visibleBounds
        for (down in listOf(true, false)) repeat(8) {
            val (from, to) = if (down) r.top + r.height() * 3 / 4 to r.top + r.height() / 4 else r.top + r.height() / 4 to r.top + r.height() * 3 / 4
            dev.swipe(r.centerX(), from, r.centerX(), to, 30)
            if (waitFresh(300) { dev.hasObject(By.text(text)) }) return node(text, By::text)
        }
        return node(text, By::text)
    }

    private fun themes(enabled: Boolean) {
        sheetShow(Copy.themeName(ReadingThemeId.WARM), down = false)
        for (id in ReadingThemeId.entries) enabled(Copy.themeName(id), enabled)
        assertTrue("the saved theme stays marked", choice(Copy.themeName(ReadingThemeId.WARM)).isChecked)
        shown(Copy.saverTheme, !enabled)
    }
    private fun pageNumbers(enabled: Boolean) {
        sheetShow(Copy.segmentChapterPages)
        for (t in listOf(Copy.segmentBookPages, Copy.segmentScreenPages, Copy.segmentChapterPages)) enabled(t, enabled)
        assertTrue("the saved page numbers stay marked", choice(Copy.segmentScreenPages).isChecked)
        shown(Copy.saverPageNumbers, !enabled)
    }
    private fun animation(enabled: Boolean) {
        for (t in listOf(Copy.animSlide, Copy.animNone)) enabled(t, enabled)
        assertTrue("the saved animation stays marked", choice(Copy.animSlide).isChecked)
        shown(Copy.saverPageAnimation, !enabled)
    }
    private fun ActivityScenario<MainActivity>.tab(t: ReaderSheetTab) {
        onActivity { it.controller.selectSheetTab(t) }
        shown(if (t == ReaderSheetTab.DISPLAY) Copy.textSize else Copy.swipeDirection, true)
    }

    @Test fun battery_saver_greys_out_what_it_overrides_in_the_sheet_and_on_the_settings_screen() = withBook(chosen) { sc, _ ->
        val c = sc.read { it.controller }
        sc.onActivity { it.controller.showSheet(ReaderSheetTab.DISPLAY) }
        shown(Copy.textSize, true)
        themes(enabled = true); pageNumbers(enabled = true)

        powerSave(true)
        pageNumbers(enabled = false)
        sheetShow(Copy.followBatterySaver); enabledDesc(Copy.followBatterySaver, true) // nothing else is disabled
        themes(enabled = false)
        enabledDesc(Copy.largerText, true)
        sc.tab(ReaderSheetTab.GENERAL)
        animation(enabled = false)
        enabled(Copy.swipeAuto, true)

        // "Follow Battery Saver" off: enabled again at once, on the open sheet; and back.
        sc.onActivity { it.controller.updateSettings { s -> s.copy(followBatterySaver = false) } }
        animation(enabled = true)
        sc.onActivity { it.controller.updateSettings { s -> s.copy(followBatterySaver = true) } }
        animation(enabled = false)

        // Battery Saver off: enabled at once, and they work.
        powerSave(false)
        animation(enabled = true)
        choice(Copy.animNone).click()
        withTimeout(5_000) { c.settings.first { it.pageAnimation == PageAnimation.NONE } }
        sc.tab(ReaderSheetTab.DISPLAY)
        themes(enabled = true)
        choice(Copy.themeName(ReadingThemeId.LIGHT)).click()
        withTimeout(5_000) { c.settings.first { it.theme == ReadingThemeId.LIGHT } }
        pageNumbers(enabled = true)
        choice(Copy.segmentChapterPages).click()
        withTimeout(5_000) { c.settings.first { it.pageNumbers == PageNumbers.SCREEN_CHAPTER } }

        // The Settings screen: Page numbers and Page-turn animation, with the saved values shown.
        sc.onActivity { it.controller.hideSheet(); it.nav.push(Route.Settings) }
        val rows = listOf(Copy.pageNumbers to Copy.saverPageNumbers, Copy.pageAnimation to Copy.saverPageAnimation)
        for ((title, note) in rows) { settingsShow(title); enabled(title, true); shown(note, false) }
        powerSave(true)
        for ((title, note) in rows) { settingsShow(title); enabled(title, false); shown(note, true) }
        settingsShow(Copy.pageNumbersScreenChapter); settingsShow(Copy.animNone) // the saved choices, still shown
        settingsShow(Copy.swipeDirection); enabled(Copy.swipeDirection, true) // nothing else
        powerSave(false)
        for ((title, note) in rows) { settingsShow(title); enabled(title, true); shown(note, false) }
        sc.onActivity { it.nav.pop() }
        assertFalse(c.batterySaving.value)
    }

    private companion object {
        // ReadingTheme.Warm / Black backgrounds, as SystemThemeTest.
        const val WARM_CSS = "rgb(244, 233, 211)"
        const val BLACK_CSS = "rgb(0, 0, 0)"
    }
}
