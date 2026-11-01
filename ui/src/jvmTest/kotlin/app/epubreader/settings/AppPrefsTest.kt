package app.epubreader.settings

import app.epubreader.core.data.SettingsPort
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AppPrefsTest {
    private class FakeSettings : SettingsPort {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value }
    }

    @Test fun defaults_then_changes_persist_and_apply_at_once() = runTest {
        val store = FakeSettings()
        val a = AppPrefs(store, backgroundScope); runCurrent()
        assertEquals(AppThemeMode.SYSTEM, a.theme.value); assertEquals(true, a.folderView.value) // C-074: on by default
        a.setTheme(AppThemeMode.DARK); a.setFolderView(false)
        assertEquals(AppThemeMode.DARK, a.theme.value) // applied without waiting for the store (no restart)
        runCurrent()
        val b = AppPrefs(store, backgroundScope); runCurrent()
        assertEquals(AppThemeMode.DARK, b.theme.value); assertEquals(false, b.folderView.value)
    }

    @Test fun unknown_stored_values_fall_back_to_defaults() = runTest {
        val store = FakeSettings().apply { map[AppPrefs.THEME] = "PURPLE"; map[AppPrefs.FOLDER_VIEW] = "maybe" }
        val a = AppPrefs(store, backgroundScope); runCurrent()
        assertEquals(AppThemeMode.SYSTEM, a.theme.value); assertEquals(true, a.folderView.value)
    }

    @Test fun initial_theme_avoids_a_flash() = runTest {
        val a = AppPrefs(FakeSettings(), backgroundScope, initialTheme = AppThemeMode.LIGHT)
        assertEquals(AppThemeMode.LIGHT, a.theme.value)
    }
}
