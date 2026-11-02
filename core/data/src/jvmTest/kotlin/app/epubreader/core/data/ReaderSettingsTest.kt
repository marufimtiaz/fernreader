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
        val s = ReaderSettings(1.4, ReaderSettings.LITERATA, 1.8, 3, Align.JUSTIFY, ReadingThemeId.WARM, Flow.AUTO, 0.4, PageNumbers.SCREEN_CHAPTER)
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

    @Test fun m6_defaults() {
        val d = ReaderSettings()
        assertEquals(SwipeDirection.AUTO, d.swipeDirection)
        assertEquals(false, d.tapZones)
        assertEquals(VolumeKeys.OFF, d.volumeKeys)
        assertEquals(PageAnimation.SLIDE, d.pageAnimation)
        assertEquals(true, d.fullscreen)
        assertEquals(true, d.keepScreenOn)
        assertEquals(OrientationLock.SYSTEM, d.orientation)
        assertEquals(true, d.confirmExternalLinks)
    }

    @Test fun m6_round_trip() {
        val s = ReaderSettings(
            swipeDirection = SwipeDirection.RTL, tapZones = true, volumeKeys = VolumeKeys.REVERSED, pageAnimation = PageAnimation.NONE,
            fullscreen = false, keepScreenOn = false, orientation = OrientationLock.LANDSCAPE, confirmExternalLinks = false,
        )
        assertEquals(s, ReaderSettings.decode(s.encode()))
    }

    @Test fun m6_bad_values_fall_back_to_defaults() {
        val bad = ReaderSettings.decode(
            """{"swipeDirection":"UP","tapZones":"yes","volumeKeys":3,"pageAnimation":"FLIP","fullscreen":1,"keepScreenOn":"false","orientation":"SIDEWAYS","confirmExternalLinks":null}""",
        )
        assertEquals(ReaderSettings(), bad)
        assertEquals(false, ReaderSettings.decode("""{"fullscreen":false,"tapZones":true}""").fullscreen)
        assertEquals(true, ReaderSettings.decode("""{"fullscreen":false,"tapZones":true}""").tapZones)
    }

    @Test fun settings_stored_by_m5_decode_with_m6_defaults() {
        val m5 = """{"fontScale":1.4,"fontFamily":"literata","lineHeight":1.8,"marginPercent":3,"align":"JUSTIFY","theme":"WARM","flow":"AUTO","brightness":0.4,"pageIndicatorScope":"CHAPTER"}"""
        val s = ReaderSettings.decode(m5)
        assertEquals(1.4, s.fontScale)
        assertEquals(ReadingThemeId.WARM, s.theme)
        assertEquals(ReaderSettings(1.4, ReaderSettings.LITERATA, 1.8, 3, Align.JUSTIFY, ReadingThemeId.WARM, Flow.AUTO, 0.4, PageNumbers.SCREEN_CHAPTER), s)
    }

    @Test fun highlight_colour_defaults_to_yellow_round_trips_and_rejects_unknown_ids() {
        assertEquals(HighlightColors.YELLOW, ReaderSettings().highlightColor)
        val s = ReaderSettings(highlightColor = HighlightColors.PURPLE)
        assertEquals(s, ReaderSettings.decode(s.encode()))
        assertEquals(HighlightColors.YELLOW, ReaderSettings.decode("""{"highlightColor":"neon"}""").highlightColor)
        assertEquals(HighlightColors.YELLOW, ReaderSettings.decode("""{"highlightColor":3}""").highlightColor)
        assertEquals(HighlightColors.YELLOW, ReaderSettings(highlightColor = "x").clamped().highlightColor)
    }

    /** C-083: Book pages is the default; old stored scopes keep working (BOOK was the old default, CHAPTER a choice). */
    @Test fun page_numbers_default_and_old_scope_values() {
        assertEquals(PageNumbers.BOOK_PAGES, ReaderSettings().pageNumbers)
        assertEquals(PageNumbers.BOOK_PAGES, ReaderSettings.decode("""{"pageIndicatorScope":"BOOK"}""").pageNumbers)
        assertEquals(PageNumbers.SCREEN_CHAPTER, ReaderSettings.decode("""{"pageIndicatorScope":"CHAPTER"}""").pageNumbers)
        assertEquals(PageNumbers.SCREEN_BOOK, ReaderSettings.decode("""{"pageIndicatorScope":"CHAPTER","pageNumbers":"SCREEN_BOOK"}""").pageNumbers)
        assertEquals(PageNumbers.BOOK_PAGES, ReaderSettings.decode("""{"pageNumbers":"PAGES_OF_DOOM"}""").pageNumbers)
        for (p in PageNumbers.entries) assertEquals(p, ReaderSettings.decode(ReaderSettings(pageNumbers = p).encode()).pageNumbers)
        assertEquals(IndicatorScope.CHAPTER, PageNumbers.SCREEN_CHAPTER.screenScope)
        assertEquals(IndicatorScope.BOOK, PageNumbers.BOOK_PAGES.screenScope)
    }

    /** C-085: Scroll mode's "Chapters" defaults to Continuous; settings stored before it existed decode to that. */
    @Test fun scroll_chapters_default_round_trip_and_old_values() {
        assertEquals(ScrollChapters.CONTINUOUS, ReaderSettings().scrollChapters)
        val s = ReaderSettings(flow = Flow.SCROLLED, scrollChapters = ScrollChapters.ONE_AT_A_TIME)
        assertEquals(s, ReaderSettings.decode(s.encode()))
        val old = """{"fontScale":1.2,"flow":"SCROLLED","pageAnimation":"NONE","highlightColor":"blue"}"""
        assertEquals(ReaderSettings(fontScale = 1.2, flow = Flow.SCROLLED, pageAnimation = PageAnimation.NONE, highlightColor = "blue"), ReaderSettings.decode(old))
        assertEquals(ScrollChapters.CONTINUOUS, ReaderSettings.decode(old).scrollChapters)
        assertEquals(ScrollChapters.CONTINUOUS, ReaderSettings.decode("""{"scrollChapters":"SIDEWAYS"}""").scrollChapters)
        assertEquals(ScrollChapters.CONTINUOUS, ReaderSettings.decode("""{"scrollChapters":1}""").scrollChapters)
    }
}
