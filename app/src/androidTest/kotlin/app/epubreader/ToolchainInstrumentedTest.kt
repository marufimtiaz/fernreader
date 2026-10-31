package app.epubreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epubreader.core.data.SettingEntity
import app.epubreader.core.data.androidDatabaseBuilder
import app.epubreader.core.data.buildDatabase
import app.epubreader.core.search.Fts5Capability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolchainInstrumentedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun room_kmp_works_on_device() = runBlocking {
        val db = buildDatabase(androidDatabaseBuilder(ctx))
        db.settingDao().upsert(SettingEntity("t", "probe", "1", 1, false))
        assertEquals("1", db.settingDao().get("probe")?.json)
    }

    @Test fun fts5_available_on_device() {
        val r = Fts5Capability.check(ctx.cacheDir.resolve("fts-probe.db").path)
        assertTrue("FTS5 missing (${r.sqliteVersion})", r.fts5 && r.diacriticsFolded)
    }
}
