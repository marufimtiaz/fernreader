package app.fernreader.settings

import android.content.Context

/**
 * A copy of the app theme (F-074) in SharedPreferences, read before the first frame so a dark or light choice never
 * flashes the other theme at start (the database opens lazily, off the main thread). The `setting` table stays the
 * source of truth (backups, sync); MainActivity writes this copy whenever the theme changes.
 */
object AppThemeCache {
    private const val FILE = "app-theme"
    private const val KEY = "theme"

    fun read(c: Context): AppThemeMode =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)?.let { v -> AppThemeMode.entries.firstOrNull { it.name == v } } ?: AppThemeMode.SYSTEM

    fun write(c: Context, t: AppThemeMode) {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (p.getString(KEY, null) != t.name) p.edit().putString(KEY, t.name).apply()
    }
}
