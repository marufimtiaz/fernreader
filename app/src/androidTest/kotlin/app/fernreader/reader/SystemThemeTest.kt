package app.fernreader.reader

import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fernreader.MainActivity
import app.fernreader.TestBooks
import app.fernreader.core.data.ReaderSettings
import app.fernreader.core.data.ReadingThemeId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C-105: under the System theme (the default) the reader's page is Light while the phone is in light mode and true
 * Black while it is in dark mode, and it switches live, with the book open, when the phone switches (`cmd uimode night`).
 * A theme the reader chose stays as it is. The phone is put back in light mode after each test.
 */
@RunWith(AndroidJUnit4::class)
class SystemThemeTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun shell(cmd: String): String {
        val fd = inst.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }.trim()
    }
    private fun night(on: Boolean) { shell("cmd uimode night ${if (on) "yes" else "no"}") }

    @Before fun lightMode() = night(false)
    @After fun backToLightMode() = night(false)

    /** The page's background as the WebView paints it ("rgb(r, g, b)"). */
    private suspend fun ActivityScenario<MainActivity>.pageBackground(): String =
        JSONTokener(js("getComputedStyle(document.body).backgroundColor")).nextValue() as String

    /** A pixel of the screen in the page's side margin, half way down: what the reader actually sees. */
    private fun screenPixel(): Int {
        val bmp = inst.uiAutomation.takeScreenshot()
        try { return bmp.getPixel((bmp.width * 0.02).toInt(), bmp.height / 2) } finally { bmp.recycle() }
    }

    private suspend fun ActivityScenario<MainActivity>.shows(what: String, css: String, rgb: Int) {
        val end = System.currentTimeMillis() + 5_000
        while ((pageBackground() != css || screenPixel() != rgb) && System.currentTimeMillis() < end) delay(100)
        assertEquals("$what: the page's background", css, pageBackground())
        assertEquals("$what: the screen", Integer.toHexString(rgb), Integer.toHexString(screenPixel()))
    }

    private fun withBook(theme: ReadingThemeId, body: suspend (ActivityScenario<MainActivity>, ReaderHarness) -> Unit) = runBlocking {
        val f = TestBooks.write(inst.targetContext, "systheme.epub", TestBooks.epub(id = "urn:uuid:sys-${System.nanoTime()}", chapters = 2, paragraphs = 30))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { s -> s.copy(theme = theme) } }
                h.open(f)
                body(sc, h)
            } finally {
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.a.settingsStored()
                h.stop()
            }
        }
    }

    @Test fun system_theme_follows_the_phone_live() = withBook(ReadingThemeId.SYSTEM) { sc, _ ->
        sc.shows("light mode", LIGHT_CSS, LIGHT)
        night(true)
        sc.shows("dark mode", BLACK_CSS, BLACK)
        night(false)
        sc.shows("light mode again", LIGHT_CSS, LIGHT)
    }

    @Test fun a_chosen_theme_ignores_the_phone() = withBook(ReadingThemeId.WARM) { sc, _ ->
        sc.shows("warm in light mode", WARM_CSS, WARM)
        night(true)
        delay(1_000) // the window in which it must not change: fixed
        sc.shows("warm in dark mode", WARM_CSS, WARM)
    }

    private companion object {
        // ReadingTheme.Light / Black / Warm backgrounds (ReadingThemeTest keeps the web side's in step).
        val LIGHT = Color.rgb(0xFB, 0xFA, 0xF7); const val LIGHT_CSS = "rgb(251, 250, 247)"
        val BLACK = Color.rgb(0, 0, 0); const val BLACK_CSS = "rgb(0, 0, 0)"
        val WARM = Color.rgb(0xF4, 0xE9, 0xD3); const val WARM_CSS = "rgb(244, 233, 211)"
    }
}
