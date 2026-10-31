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

    @Test fun v2_to_v3_keeps_books_files_and_positions() {
        val dbPath = Files.createTempDirectory("mig").resolve("m.db")
        val helper = MigrationTestHelper(
            schemaDirectoryPath = Path.of("schemas"), databasePath = dbPath,
            driver = BundledSQLiteDriver(), databaseClass = AppDatabase::class,
        )
        helper.createDatabase(2).also { insertM2Book(it) }.close()
        val migrated = helper.runMigrationsAndValidate(3)
        migrated.prepare("SELECT cfi FROM reading_position WHERE bookId = 'b1'").use { assertEquals(true, it.step()); assertEquals("epubcfi(/6/4!/4/2/1:0)", it.getText(0)) }
        migrated.prepare("SELECT COUNT(*) FROM scan_failure").use { assertEquals(true, it.step()); assertEquals(0L, it.getLong(0)) }
        migrated.close()
    }

    companion object {
        /** A book as M2 recorded it after a picker open: identity from foliate, a content:// file row, a position. */
        fun insertM2Book(c: androidx.sqlite.SQLiteConnection, identityKey: String = "k", uri: String = "content://com.android.providers.downloads.documents/document/42") {
            c.execSQL("INSERT INTO book (id, identityKey, title, sortTitle, authors, sortAuthor, language, titleDirection, coverThumb, addedAt, lastOpenedAt, hidden, updatedAt, deleted) " +
                "VALUES ('b1', '$identityKey', 'WS Book', 'ws book', 'Test Author', 'test author', NULL, 'auto', NULL, 100, 200, 0, 200, 0)")
            c.execSQL("INSERT INTO book_file (path, bookId, source, folder, size, mtimeMs, lastSeenScan) VALUES ('$uri', 'b1', 'opened', '', 10, 0, 0)")
            c.execSQL("INSERT INTO reading_position (id, bookId, cfi, fraction, spineIndex, updatedAt, deleted) VALUES ('p1', 'b1', 'epubcfi(/6/4!/4/2/1:0)', 0.25, 1, 300, 0)")
        }
    }
}
