package app.epubreader.core.data

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingDaoTest {
    private fun db() = buildDatabase(jvmDatabaseBuilder(Files.createTempDirectory("db").resolve("t.db").toString()))

    @Test fun upsert_then_read_back() = runTest {
        val dao = db().settingDao()
        dao.upsert(SettingEntity(id = "s1", key = "theme", json = "\"dark\"", updatedAt = 10, deleted = false))
        assertEquals("\"dark\"", dao.get("theme")?.json)
    }

    @Test fun tombstoned_setting_is_not_returned() = runTest {
        val dao = db().settingDao()
        dao.upsert(SettingEntity(id = "s1", key = "theme", json = "\"dark\"", updatedAt = 10, deleted = true))
        assertNull(dao.get("theme"))
    }
}
