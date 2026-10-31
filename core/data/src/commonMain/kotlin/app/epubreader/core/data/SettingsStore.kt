package app.epubreader.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** String settings as the UI sees them (a seam for tests). */
interface SettingsPort {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
}

/** [SettingsPort] over the synced `setting` table; values are stored as JSON strings. */
class SettingsStore(private val db: AppDatabase, private val clock: HybridClock) : SettingsPort {
    override suspend fun get(key: String): String? =
        db.settingDao().get(key)?.json?.let { runCatching { Json.parseToJsonElement(it).jsonPrimitive.content }.getOrNull() }

    override suspend fun put(key: String, value: String) {
        val id = db.settingDao().get(key)?.id ?: newId()
        db.settingDao().upsert(SettingEntity(id, key, JsonPrimitive(value).toString(), clock.now(), deleted = false))
    }

    companion object {
        const val LIBRARY_SCOPE = "library.scope" // "everywhere" or an absolute folder path (picker UI: M7)
        const val LIBRARY_LAYOUT = "library.layout"
        const val LIBRARY_SORT = "library.sort"
    }
}
