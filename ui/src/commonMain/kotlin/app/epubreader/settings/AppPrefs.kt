package app.epubreader.settings

import app.epubreader.core.data.SettingsPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** F-074 app UI theme. */
enum class AppThemeMode { SYSTEM, LIGHT, DARK }

/**
 * App-wide settings that are not reading settings (F-100): app theme, folder view (F-017). Changes apply at once (the
 * flows update before the store is written) and persist in the synced `setting` table. [initialTheme] is a copy kept
 * outside the database so the first frame already has the right theme (P-001: no database read before it).
 */
class AppPrefs(private val store: SettingsPort, private val scope: CoroutineScope, initialTheme: AppThemeMode = AppThemeMode.SYSTEM) {
    private val _theme = MutableStateFlow(initialTheme)
    private val _folderView = MutableStateFlow(false)
    val theme: StateFlow<AppThemeMode> = _theme
    val folderView: StateFlow<Boolean> = _folderView

    init {
        scope.launch {
            _theme.value = store.get(THEME)?.let { v -> AppThemeMode.entries.firstOrNull { it.name == v } } ?: AppThemeMode.SYSTEM
            _folderView.value = store.get(FOLDER_VIEW) == "true"
        }
    }

    fun setTheme(t: AppThemeMode) { _theme.value = t; scope.launch { store.put(THEME, t.name) } }
    fun setFolderView(on: Boolean) { _folderView.value = on; scope.launch { store.put(FOLDER_VIEW, on.toString()) } }

    companion object {
        const val THEME = "app.theme"
        const val FOLDER_VIEW = "library.folderView"
    }
}
