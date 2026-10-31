package app.epubreader.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/** Every schema version from the first one with user data (2) on must migrate; M1's v1 must reach v2. */
class MigrationTest {
    @Test fun v1_to_v2() {
        val dbPath = Files.createTempDirectory("mig").resolve("m.db")
        val helper = MigrationTestHelper(
            schemaDirectoryPath = Path.of("schemas"), databasePath = dbPath,
            driver = BundledSQLiteDriver(), databaseClass = AppDatabase::class,
        )
        helper.createDatabase(1).close()
        helper.runMigrationsAndValidate(2).close()
    }
}
