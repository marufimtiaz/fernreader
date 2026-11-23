package app.fernreader.reader.display

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.fernreader.core.data.ReadingThemeId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadingThemeTest {
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance() + 0.05, b.luminance() + 0.05).sortedDescending()
        return hi.toDouble() / lo
    }

    @Test fun every_theme_reads_comfortably() {
        for (id in ReadingThemeId.entries) {
            val t = ReadingTheme.of(id, systemDark = false)
            assertTrue(contrast(t.text, t.background) >= 7.0, "$id text ${contrast(t.text, t.background)}") // WCAG AAA body text
            assertTrue(contrast(t.link, t.background) >= 4.5, "$id link ${contrast(t.link, t.background)}")
            assertEquals(t.background, t.colorScheme().background)
            assertEquals(t.isLight, t.colorScheme().background.luminance() > 0.5f)
        }
        assertEquals(4, ReadingThemeId.entries.map { ReadingTheme.of(it, systemDark = false).background }.toSet().size)
    }

    /** C-105: System is Light in light mode and true Black in dark mode; the other themes ignore the phone's mode. */
    @Test fun system_follows_the_phone_dark_mode() {
        assertEquals(ReadingTheme.Light, ReadingTheme.of(ReadingThemeId.SYSTEM, systemDark = false))
        assertEquals(ReadingTheme.Black, ReadingTheme.of(ReadingThemeId.SYSTEM, systemDark = true))
        assertEquals(Color(0xFF000000), ReadingTheme.of(ReadingThemeId.SYSTEM, systemDark = true).background)
        for (id in ReadingThemeId.entries - ReadingThemeId.SYSTEM) {
            assertEquals(ReadingTheme.of(id, systemDark = false), ReadingTheme.of(id, systemDark = true))
        }
    }

    /** The web side (styles.ts) paints the page; the app paints the strips and the panel: same colours. */
    @Test fun palette_matches_the_web_styles() {
        val css = File("../reader-web/src/reader/styles.ts").readText()
        for (id in ReadingThemeId.entries - ReadingThemeId.SYSTEM) { // System has no colours of its own
            val t = ReadingTheme.of(id, systemDark = false)
            val line = css.lines().first { it.trimStart().startsWith("${id.name.lowercase()}: { bg:") }
            for (c in listOf(t.background, t.text, t.link)) assertTrue(hex(c) in line, "$id ${hex(c)} not in: $line")
        }
    }

    private fun hex(c: Color) = "#%06X".format(c.value.toLong().ushr(32).toInt() and 0xFFFFFF)
}
