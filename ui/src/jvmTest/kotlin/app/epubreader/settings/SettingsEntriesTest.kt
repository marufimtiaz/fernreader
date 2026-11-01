package app.epubreader.settings

import app.epubreader.core.data.IndicatorScope
import app.epubreader.core.data.OrientationLock
import app.epubreader.core.data.PageAnimation
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.SwipeDirection
import app.epubreader.core.data.VolumeKeys
import app.epubreader.ui.Copy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** F-100: the M6 reader settings are on the settings screen, show the current values and change the reader's settings. */
class SettingsEntriesTest {
    private var reader = ReaderSettings()

    private fun sections() = settingsSections(SettingsInputs(
        theme = AppThemeMode.SYSTEM, onTheme = {}, folderView = false, onFolderView = {}, folder = null, onEverywhere = {}, onPickFolder = {},
        hiddenCount = 0, onHiddenBooks = {},
        reader = reader, onReader = { change -> reader = change(reader) },
        onBackUp = {}, onRestore = {}, onAbout = {},
    ))

    private fun item(id: String) = sections().flatMap { it.items }.single { it.id == id }
    private fun choice(id: String) = item(id) as SettingItem.Choice
    private fun toggle(id: String) = item(id) as SettingItem.Toggle

    @Test fun every_m6_reader_setting_is_listed_in_its_section() {
        val byTitle = sections().associate { s -> s.title to s.items.map { it.id } }
        assertEquals(listOf("reader.pageScope", "reader.pageAnimation", "reader.swipeDirection", "reader.tapZones", "reader.volumeKeys"), byTitle[Copy.sectionReading])
        assertEquals(listOf("reader.fullscreen", "reader.keepScreenOn", "reader.orientation"), byTitle[Copy.settingsScreen])
        assertEquals(listOf("reader.confirmLinks"), byTitle[Copy.settingsLinks])
        // the sections sit between Library and Backup
        assertEquals(listOf(Copy.sectionAppearance, Copy.sectionLibrary, Copy.sectionReading, Copy.settingsScreen, Copy.settingsLinks, Copy.sectionBackup, Copy.about),
            sections().map { it.title })
        val ids = sections().flatMap { it.items }.map { it.id }
        assertEquals(ids.size, ids.toSet().size) // lazy-list keys
    }

    @Test fun items_show_the_current_values() {
        reader = ReaderSettings(pageIndicatorScope = IndicatorScope.CHAPTER, pageAnimation = PageAnimation.NONE, swipeDirection = SwipeDirection.RTL,
            tapZones = true, volumeKeys = VolumeKeys.REVERSED, fullscreen = false, keepScreenOn = false, orientation = OrientationLock.LANDSCAPE,
            confirmExternalLinks = false)
        assertEquals("CHAPTER", choice("reader.pageScope").selected)
        assertEquals("NONE", choice("reader.pageAnimation").selected)
        assertEquals("RTL", choice("reader.swipeDirection").selected)
        assertTrue(toggle("reader.tapZones").checked)
        assertEquals("REVERSED", choice("reader.volumeKeys").selected)
        assertEquals(Copy.volumeSummary(VolumeKeys.REVERSED), choice("reader.volumeKeys").summary) // C-005: which key does what
        assertEquals(false, toggle("reader.fullscreen").checked)
        assertEquals(false, toggle("reader.keepScreenOn").checked)
        assertEquals("LANDSCAPE", choice("reader.orientation").selected)
        assertEquals(false, toggle("reader.confirmLinks").checked)
        assertEquals(Copy.tapZones, toggle("reader.tapZones").title)
    }

    @Test fun defaults_are_shown_for_a_fresh_install() {
        assertEquals(false, toggle("reader.tapZones").checked)
        assertEquals("OFF", choice("reader.volumeKeys").selected) // C-005
        assertTrue(Copy.volumeSummary(VolumeKeys.OFF).contains(Copy.volumeHint(VolumeKeys.OFF)))
        assertEquals(true, toggle("reader.confirmLinks").checked)
    }

    @Test fun changing_an_item_updates_the_reader_settings() {
        toggle("reader.tapZones").onChange(true); assertTrue(reader.tapZones)
        choice("reader.volumeKeys").onSelect("ON"); assertEquals(VolumeKeys.ON, reader.volumeKeys)
        choice("reader.pageAnimation").onSelect("NONE"); assertEquals(PageAnimation.NONE, reader.pageAnimation)
        choice("reader.swipeDirection").onSelect("LTR"); assertEquals(SwipeDirection.LTR, reader.swipeDirection)
        toggle("reader.fullscreen").onChange(false); assertEquals(false, reader.fullscreen)
        toggle("reader.keepScreenOn").onChange(false); assertEquals(false, reader.keepScreenOn)
        choice("reader.orientation").onSelect("PORTRAIT"); assertEquals(OrientationLock.PORTRAIT, reader.orientation)
        toggle("reader.confirmLinks").onChange(false); assertEquals(false, reader.confirmExternalLinks)
        choice("reader.pageScope").onSelect("CHAPTER"); assertEquals(IndicatorScope.CHAPTER, reader.pageIndicatorScope)
        // one field at a time: the others are untouched
        assertEquals(ReaderSettings().fontScale, reader.fontScale); assertTrue(reader.tapZones)
    }
}
