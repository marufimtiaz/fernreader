package app.epubreader.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderSettingsTest {
    @Test fun numbers_are_clamped_and_rounded_to_their_step() {
        assertEquals(1.2, ReaderSettings(fontScale = 1.2499).clamped().fontScale)
        assertEquals(2.6, ReaderSettings(fontScale = 3.0).clamped().fontScale)
        assertEquals(0.8, ReaderSettings(fontScale = 0.7).clamped().fontScale)
        assertEquals(1.3, ReaderSettings(lineHeight = 1.0).clamped().lineHeight)
        assertEquals(2.0, ReaderSettings(lineHeight = 2.04).clamped().lineHeight)
        assertEquals(1.7, ReaderSettings(lineHeight = 1.66).clamped().lineHeight)
        assertEquals(0, ReaderSettings(marginPercent = -1).clamped().marginPercent)
        assertEquals(10, ReaderSettings(marginPercent = 11).clamped().marginPercent)
        assertEquals(0.05, ReaderSettings(brightness = 0.0).clamped().brightness)
        assertEquals(null, ReaderSettings(brightness = null).clamped().brightness)
        assertEquals(ReaderSettings.PUBLISHER, ReaderSettings(fontFamily = "comic").clamped().fontFamily)
    }

    @Test fun font_steps_stop_at_the_ends() {
        assertEquals(2.6, ReaderSettings(fontScale = 2.6).stepFont(+1).fontScale)
        assertEquals(0.8, ReaderSettings(fontScale = 0.8).stepFont(-1).fontScale)
        assertEquals(1.1, ReaderSettings().stepFont(+1).fontScale)
        // 19 steps from 0.8 to 2.6 (F-070: 10 % steps, 80 %..260 %)
        var s = ReaderSettings(fontScale = 0.8); var n = 0
        while (s.fontScale < 2.6) { s = s.stepFont(+1); n++ }
        assertEquals(18, n)
    }

    @Test fun round_trip() {
        val s = ReaderSettings(1.4, ReaderSettings.LITERATA, 1.8, 3, Align.JUSTIFY, ReadingThemeId.WARM, Flow.AUTO, 0.4, IndicatorScope.CHAPTER)
        assertEquals(s, ReaderSettings.decode(s.encode()))
    }

    @Test fun decodes_garbage_to_defaults() {
        assertEquals(ReaderSettings(), ReaderSettings.decode(null))
        assertEquals(ReaderSettings(), ReaderSettings.decode("{"))
        assertEquals(ReaderSettings(), ReaderSettings.decode("[1,2]"))
        assertEquals(ReaderSettings(), ReaderSettings.decode("""{"fontScale":"x"}"""))
        assertEquals(ReaderSettings(fontScale = 2.6, align = Align.START),
            ReaderSettings.decode("""{"theme":"neon","fontScale":9,"extra":1,"align":"START","brightness":"dim"}"""))
    }
}
