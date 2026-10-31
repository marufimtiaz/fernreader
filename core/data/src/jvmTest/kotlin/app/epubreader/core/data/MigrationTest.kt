package app.epubreader.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every schema version from the first one with user data (2) on must migrate; M1's v1 must reach v2. */
class MigrationTest {
    @Test fun v1_to_v2_keeps_settings() {
        val dbPath = Files.createTempDirectory("mig").resolve("m.db")
        val helper = MigrationTestHelper(
            schemaDirectoryPath = Path.of("schemas"), databasePath = dbPath,
            driver = BundledSQLiteDriver(), databaseClass = AppDatabase::class,
        )
        helper.createDatabase(1).also {
            it.execSQL("INSERT INTO setting (id, key, json, updatedAt, deleted) VALUES ('s1', 'theme', '\"dark\"', 10, 0)")
        }.close()
        val migrated = helper.runMigrationsAndValidate(2)
        migrated.prepare("SELECT json FROM setting WHERE key = 'theme'").use {
            assertEquals(true, it.step())
            assertEquals("\"dark\"", it.getText(0))
        }
        migrated.close()
    }
}
