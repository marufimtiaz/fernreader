package app.fernreader.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        assertEquals(ReaderSettings.MIN_BRIGHTNESS, ReaderSettings(brightness = 0.0).clamped().brightness) // C-109
        assertEquals(0.0123, ReaderSettings(brightness = 0.012345).clamped().brightness) // fine enough for the dim range
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

    @Test fun c095_the_sheet_tab_is_stored_and_a_value_without_it_opens_display() {
        assertEquals(ReaderSheetTab.GENERAL, ReaderSettings.decode(ReaderSettings(sheetTab = ReaderSheetTab.GENERAL).encode()).sheetTab)
        assertEquals(ReaderSheetTab.DISPLAY, ReaderSettings.decode("""{"fontScale":1.2}""").sheetTab)
        assertEquals(ReaderSheetTab.DISPLAY, ReaderSettings.decode("""{"sheetTab":"OTHER"}""").sheetTab)
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
        assertEquals(false, d.keepScreenOn) // C-106
        assertEquals(OrientationLock.SYSTEM, d.orientation)
        assertEquals(true, d.confirmExternalLinks)
    }

    @Test fun m6_round_trip() {
        val s = ReaderSettings(
            swipeDirection = SwipeDirection.RTL, tapZones = true, volumeKeys = VolumeKeys.REVERSED, pageAnimation = PageAnimation.NONE,
            fullscreen = false, keepScreenOn = true, orientation = OrientationLock.LANDSCAPE, confirmExternalLinks = false,
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

    /**
     * C-109: older versions stored the window brightness, linear, 0.05..1. It is still stored that way, so each old value
     * gives the same window brightness after the update; the slider shows it on the system's scale.
     */
    @Test fun c109_brightness_stored_by_older_versions_keeps_the_screen_as_it_was() {
        for (old in listOf(0.05, 0.4, 0.5, 1.0)) {
            val s = ReaderSettings.decode("""{"fontScale":1.2,"brightness":$old}""")
            assertEquals(old, s.brightness, "window brightness for stored $old")
            assertEquals(s, ReaderSettings.decode(s.encode()))
        }
        // Where the slider shows them now (min 0): the old middle, 0.5 linear, is near the system slider's 87 %.
        assertEquals(0.8716, BrightnessCurve.toPosition(ReaderSettings.decode("""{"brightness":0.5}""").brightness!!, 0.0, 1.0), 1e-3)
        assertEquals(1.0, BrightnessCurve.toPosition(ReaderSettings.decode("""{"brightness":1.0}""").brightness!!, 0.0, 1.0), 1e-4)
        // A slider value round-trips through storage closely enough to keep the screen unchanged.
        val p = 0.3
        val stored = ReaderSettings.decode(ReaderSettings(brightness = BrightnessCurve.toLinear(p, 0.0035, 1.0)).clamped().encode()).brightness!!
        assertEquals(p, BrightnessCurve.toPosition(stored, 0.0035, 1.0), 2e-3)
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

    /** C-105/C-106: the new defaults. */
    @Test fun c105_c106_defaults() {
        assertEquals(ReadingThemeId.SYSTEM, ReaderSettings().theme)
        assertEquals(false, ReaderSettings().keepScreenOn)
        assertEquals(ReadingThemeId.LIGHT, ReadingThemeId.SYSTEM.resolve(systemDark = false))
        assertEquals(ReadingThemeId.BLACK, ReadingThemeId.SYSTEM.resolve(systemDark = true))
        assertEquals(ReadingThemeId.WARM, ReadingThemeId.WARM.resolve(systemDark = true))
        assertEquals(ReadingThemeId.LIGHT, ReadingThemeId.LIGHT.resolve(systemDark = true))
    }

    /**
     * C-105/C-106 migration. Settings stored before them hold only what differed from the old defaults (Light,
     * keep-screen-on on): a theme or keep-screen-on missing from them was never changed and moves to the new default;
     * one they hold was chosen and is kept.
     */
    @Test fun c105_c106_settings_stored_before_move_to_the_new_defaults_unless_chosen() {
        val untouched = ReaderSettings.decode("""{"fontScale":1.2,"flow":"AUTO"}""")
        assertEquals(ReadingThemeId.SYSTEM, untouched.theme)
        assertEquals(false, untouched.keepScreenOn)
        assertEquals(1.2, untouched.fontScale)
        assertEquals(ReadingThemeId.SYSTEM, ReaderSettings.decode("{}").theme)
        val chosen = ReaderSettings.decode("""{"theme":"WARM","keepScreenOn":false}""")
        assertEquals(ReadingThemeId.WARM, chosen.theme)
        assertEquals(false, chosen.keepScreenOn)
        for (t in listOf("LIGHT", "DARK", "BLACK")) assertEquals(t, ReaderSettings.decode("""{"theme":"$t"}""").theme.name)
        assertEquals(true, ReaderSettings.decode("""{"keepScreenOn":true}""").keepScreenOn)
    }

    /** From now on a choice equal to today's default is stored too, so a later change of default keeps it. */
    @Test fun chosen_values_are_stored_even_when_equal_to_the_default() {
        val d = ReaderSettings()
        assertEquals("{}", d.encode()) // nothing chosen: nothing stored, every field follows the defaults
        // The reader picks Light, then System again, and turns keep-screen-on on and off again.
        val s = d.copy(theme = ReadingThemeId.LIGHT).withChoicesFrom(d)
            .let { it.copy(theme = ReadingThemeId.SYSTEM).withChoicesFrom(it) }
            .let { it.copy(keepScreenOn = true).withChoicesFrom(it) }
            .let { it.copy(keepScreenOn = false).withChoicesFrom(it) }
        assertEquals(setOf("theme", "keepScreenOn"), s.chosen)
        val json = s.encode()
        assertTrue("\"theme\":\"SYSTEM\"" in json, json)
        assertTrue("\"keepScreenOn\":false" in json, json)
        assertEquals(s, ReaderSettings.decode(json))
        // Fields the reader did not touch stay out, so they follow a later change of default.
        assertTrue("fullscreen" !in json && "flow" !in json, json)
    }

    @Test fun choices_accumulate_and_bad_chosen_values_are_ignored() {
        val a = ReaderSettings(chosen = setOf("theme"))
        assertEquals(setOf("theme", "flow"), a.copy(flow = Flow.SCROLLED).withChoicesFrom(a).chosen)
        assertEquals(a, a.copy().withChoicesFrom(a))
        assertEquals(emptySet(), ReaderSettings.decode("""{"chosen":"theme"}""").chosen)
        assertEquals(setOf("flow"), ReaderSettings.decode("""{"chosen":["flow",3,null]}""").chosen)
    }

    @Test fun c107_follow_battery_saver_is_on_by_default_and_round_trips() {
        assertEquals(true, ReaderSettings().followBatterySaver)
        assertEquals(true, ReaderSettings.decode("{}").followBatterySaver) // settings stored before C-107
        assertEquals(true, ReaderSettings.decode("""{"followBatterySaver":"no"}""").followBatterySaver)
        val off = ReaderSettings().copy(followBatterySaver = false).withChoicesFrom(ReaderSettings())
        assertEquals(off, ReaderSettings.decode(off.encode()))
        assertEquals(false, ReaderSettings.decode(off.encode()).followBatterySaver)
    }

    /** C-107: while Battery Saver applies, Black and no animation; nothing else changes, and the saved value is untouched. */
    @Test fun c107_effective_settings_under_battery_saver() {
        val saved = ReaderSettings(theme = ReadingThemeId.WARM, pageAnimation = PageAnimation.SLIDE, fontScale = 1.4, flow = Flow.SCROLLED,
            pageNumbers = PageNumbers.SCREEN_BOOK, keepScreenOn = true, chosen = setOf("theme"))
        assertEquals(saved, saved.effective(powerSave = false))
        val saving = saved.effective(powerSave = true)
        assertEquals(saved.copy(theme = ReadingThemeId.BLACK, pageAnimation = PageAnimation.NONE), saving)
        assertEquals(ReadingThemeId.WARM, saved.theme) // the saved settings are a value: unchanged
        assertTrue(saved.batterySaving(powerSave = true))
        // Every theme, System in light mode included, becomes Black.
        for (t in ReadingThemeId.entries) assertEquals(ReadingThemeId.BLACK, ReaderSettings(theme = t).effective(true).theme.resolve(systemDark = false))
        // The setting off: nothing changes whatever the phone does.
        val off = saved.copy(followBatterySaver = false)
        assertEquals(off, off.effective(powerSave = true))
        assertEquals(false, off.batterySaving(powerSave = true))
        assertEquals(false, saved.batterySaving(powerSave = false))
    }

    @Test fun c110_extra_dim_is_off_by_default_at_half_level_and_round_trips() {
        val d = ReaderSettings()
        assertEquals(false, d.extraDim)
        assertEquals(0.5, d.extraDimLevel)
        assertEquals(1.0, d.pageDim)
        assertEquals("{}", d.encode())
        val on = d.copy(extraDim = true, extraDimLevel = 0.3).clamped()
        assertEquals(0.3, on.pageDim)
        assertEquals(on, ReaderSettings.decode(on.encode()))
        // The level is kept while Extra dim is off: switching it on again returns to it.
        val off = on.copy(extraDim = false)
        assertEquals(1.0, off.pageDim)
        assertEquals(0.3, ReaderSettings.decode(off.encode()).extraDimLevel)
    }

    @Test fun c110_extra_dim_level_is_clamped_to_70_down_to_15_percent_and_rounded() {
        assertEquals(0.7, ReaderSettings(extraDimLevel = 0.95).clamped().extraDimLevel)
        assertEquals(0.15, ReaderSettings(extraDimLevel = 0.01).clamped().extraDimLevel)
        assertEquals(0.15, ReaderSettings(extraDimLevel = -3.0).clamped().extraDimLevel)
        assertEquals(0.43, ReaderSettings(extraDimLevel = 0.4321).clamped().extraDimLevel)
        assertEquals(0.5, ReaderSettings(extraDimLevel = Double.NaN).clamped().extraDimLevel)
        assertEquals(0.7, ReaderSettings.decode("""{"extraDim":true,"extraDimLevel":2}""").pageDim)
    }

    @Test fun c110_settings_without_extra_dim_or_with_bad_values_decode_to_off() {
        val older = ReaderSettings.decode("""{"theme":"BLACK","fontScale":1.2}""")
        assertEquals(false, older.extraDim)
        assertEquals(0.5, older.extraDimLevel)
        val bad = ReaderSettings.decode("""{"extraDim":"yes","extraDimLevel":"dark"}""")
        assertEquals(false, bad.extraDim)
        assertEquals(0.5, bad.extraDimLevel)
    }
}
