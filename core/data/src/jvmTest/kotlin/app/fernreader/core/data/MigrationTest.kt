package app.fernreader.core.data

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

    @Test fun v3_to_v4_keeps_everything_and_adds_book_cache() {
        val dbPath = Files.createTempDirectory("mig").resolve("m.db")
        val helper = MigrationTestHelper(
            schemaDirectoryPath = Path.of("schemas"), databasePath = dbPath,
            driver = BundledSQLiteDriver(), databaseClass = AppDatabase::class,
        )
        helper.createDatabase(3).also {
            insertM2Book(it)
            it.execSQL("INSERT INTO setting (id, key, json, updatedAt, deleted) VALUES ('s1', 'library.sort', '\"TITLE\"', 10, 0)")
        }.close()
        val migrated = helper.runMigrationsAndValidate(4)
        migrated.prepare("SELECT cfi FROM reading_position WHERE bookId = 'b1'").use { assertEquals(true, it.step()); assertEquals("epubcfi(/6/4!/4/2/1:0)", it.getText(0)) }
        migrated.prepare("SELECT json FROM setting WHERE key = 'library.sort'").use { assertEquals(true, it.step()) }
        migrated.execSQL("INSERT INTO book_cache (bookId, kind, digest, json) VALUES ('b1', 'section-stats.v1', 'd', '[]')")
        migrated.close()
    }

    @Test fun v4_to_v5_keeps_everything_and_adds_search_history() {
        val dbPath = Files.createTempDirectory("mig").resolve("m.db")
        val helper = MigrationTestHelper(
            schemaDirectoryPath = Path.of("schemas"), databasePath = dbPath,
            driver = BundledSQLiteDriver(), databaseClass = AppDatabase::class,
        )
        helper.createDatabase(4).also {
            insertM2Book(it)
            it.execSQL("INSERT INTO book_cache (bookId, kind, digest, json) VALUES ('b1', 'section-stats.v1', 'd', '[]')")
        }.close()
        val migrated = helper.runMigrationsAndValidate(5)
        migrated.prepare("SELECT cfi FROM reading_position WHERE bookId = 'b1'").use { assertEquals(true, it.step()); assertEquals("epubcfi(/6/4!/4/2/1:0)", it.getText(0)) }
        migrated.prepare("SELECT json FROM book_cache WHERE bookId = 'b1'").use { assertEquals(true, it.step()) }
        migrated.execSQL("INSERT INTO search_history (bookId, query, usedAt) VALUES ('b1', 'prince', 5)")
        migrated.prepare("SELECT COUNT(*) FROM search_history").use { assertEquals(true, it.step()); assertEquals(1L, it.getLong(0)) }
        migrated.close()
    }

    @Test fun v5_to_v6_keeps_everything_and_adds_collections() {
        val dbPath = Files.createTempDirectory("mig").resolve("m.db")
        val helper = MigrationTestHelper(
            schemaDirectoryPath = Path.of("schemas"), databasePath = dbPath,
            driver = BundledSQLiteDriver(), databaseClass = AppDatabase::class,
        )
        helper.createDatabase(5).also {
            insertM2Book(it)
            it.execSQL("INSERT INTO book_cache (bookId, kind, digest, json) VALUES ('b1', 'section-stats.v1', 'd', '[]')")
            it.execSQL("INSERT INTO search_history (bookId, query, usedAt) VALUES ('b1', 'prince', 5)")
        }.close()
        val migrated = helper.runMigrationsAndValidate(6)
        migrated.prepare("SELECT cfi, updatedAt FROM reading_position WHERE bookId = 'b1'").use {
            assertEquals(true, it.step()); assertEquals("epubcfi(/6/4!/4/2/1:0)", it.getText(0)); assertEquals(300L, it.getLong(1))
        }
        migrated.prepare("SELECT json FROM book_cache WHERE bookId = 'b1'").use { assertEquals(true, it.step()) }
        migrated.prepare("SELECT COUNT(*) FROM search_history").use { assertEquals(true, it.step()); assertEquals(1L, it.getLong(0)) }
        migrated.prepare("SELECT hidden, hiddenUpdatedAt FROM book WHERE id = 'b1'").use { assertEquals(true, it.step()); assertEquals(0L, it.getLong(1)) }
        migrated.execSQL("INSERT INTO collection (id, name, createdAt, updatedAt, deleted) VALUES ('c1', 'Classics', 1, 1, 0)")
        migrated.execSQL("INSERT INTO collection_book (id, collectionId, bookId, updatedAt, deleted) VALUES ('m1', 'c1', 'b1', 1, 0)")
        migrated.close()
    }

    @Test fun v6_to_v7_keeps_everything_and_adds_bookmark_and_annotation() {
        val dbPath = Files.createTempDirectory("mig").resolve("m.db")
        val helper = MigrationTestHelper(
            schemaDirectoryPath = Path.of("schemas"), databasePath = dbPath,
            driver = BundledSQLiteDriver(), databaseClass = AppDatabase::class,
        )
        helper.createDatabase(6).also {
            insertM2Book(it)
            it.execSQL("INSERT INTO search_history (bookId, query, usedAt) VALUES ('b1', 'prince', 5)")
            it.execSQL("INSERT INTO collection (id, name, createdAt, updatedAt, deleted) VALUES ('c1', 'Classics', 1, 1, 0)")
        }.close()
        val migrated = helper.runMigrationsAndValidate(7)
        migrated.prepare("SELECT cfi FROM reading_position WHERE bookId = 'b1'").use { assertEquals(true, it.step()); assertEquals("epubcfi(/6/4!/4/2/1:0)", it.getText(0)) }
        migrated.prepare("SELECT query FROM search_history WHERE bookId = 'b1'").use { assertEquals(true, it.step()); assertEquals("prince", it.getText(0)) }
        migrated.prepare("SELECT name FROM collection WHERE id = 'c1'").use { assertEquals(true, it.step()); assertEquals("Classics", it.getText(0)) }
        migrated.execSQL("INSERT INTO bookmark (id, bookId, cfi, spineIndex, fraction, chapter, createdAt, updatedAt, deleted) VALUES ('m1', 'b1', 'epubcfi(/6/4!/4)', 1, 0.5, '', 1, 1, 0)")
        migrated.execSQL("INSERT INTO annotation (id, bookId, cfi, spineIndex, text, chapter, color, style, note, createdAt, updatedAt, deleted) VALUES ('a1', 'b1', 'epubcfi(/6/4!/4,/1:0,/1:3)', 1, 'abc', '', 'yellow', 'highlight', NULL, 1, 1, 0)")
        migrated.prepare("SELECT COUNT(*) FROM bookmark").use { assertEquals(true, it.step()); assertEquals(1L, it.getLong(0)) }
        migrated.prepare("SELECT COUNT(*) FROM annotation").use { assertEquals(true, it.step()); assertEquals(1L, it.getLong(0)) }
        migrated.close()
    }

    /** C-083: version 8 adds `book_pages` (C-061 reserved the number); everything else stays. */
    @Test fun v7_to_v8_keeps_everything_and_adds_book_pages() {
        val dbPath = Files.createTempDirectory("mig").resolve("m.db")
        val helper = MigrationTestHelper(
            schemaDirectoryPath = Path.of("schemas"), databasePath = dbPath,
            driver = BundledSQLiteDriver(), databaseClass = AppDatabase::class,
        )
        helper.createDatabase(7).also {
            insertM2Book(it)
            it.execSQL("INSERT INTO book_cache (bookId, kind, digest, json) VALUES ('b1', 'section-stats.v1', 'd', '[]')")
            it.execSQL("INSERT INTO bookmark (id, bookId, cfi, spineIndex, fraction, chapter, createdAt, updatedAt, deleted) VALUES ('m1', 'b1', 'epubcfi(/6/4!/4)', 1, 0.5, '', 1, 1, 0)")
            it.execSQL("INSERT INTO annotation (id, bookId, cfi, spineIndex, text, chapter, color, style, note, createdAt, updatedAt, deleted) VALUES ('a1', 'b1', 'epubcfi(/6/4!/4,/1:0,/1:3)', 1, 'abc', '', 'yellow', 'highlight', 'n', 1, 1, 0)")
        }.close()
        val migrated = helper.runMigrationsAndValidate(8)
        migrated.prepare("SELECT cfi FROM reading_position WHERE bookId = 'b1'").use { assertEquals(true, it.step()); assertEquals("epubcfi(/6/4!/4/2/1:0)", it.getText(0)) }
        migrated.prepare("SELECT json FROM book_cache WHERE bookId = 'b1'").use { assertEquals(true, it.step()) }
        migrated.prepare("SELECT COUNT(*) FROM bookmark").use { assertEquals(true, it.step()); assertEquals(1L, it.getLong(0)) }
        migrated.prepare("SELECT note FROM annotation WHERE id = 'a1'").use { assertEquals(true, it.step()); assertEquals("n", it.getText(0)) }
        migrated.prepare("SELECT COUNT(*) FROM book_pages").use { assertEquals(true, it.step()); assertEquals(0L, it.getLong(0)) }
        migrated.execSQL("INSERT INTO book_pages (bookId, digest, version, source, pageCount, labels, starts, lengths) VALUES ('b1', 'd', 1, 'VIRTUAL', 1, NULL, '0.0', '10')")
        migrated.close()
    }

    @Test fun v8_to_v9_keeps_the_library_as_it_is_with_no_separate_copies() {
        val dbPath = Files.createTempDirectory("mig").resolve("m.db")
        val helper = MigrationTestHelper(
            schemaDirectoryPath = Path.of("schemas"), databasePath = dbPath,
            driver = BundledSQLiteDriver(), databaseClass = AppDatabase::class,
        )
        helper.createDatabase(8).also {
            insertM2Book(it)
            it.execSQL("UPDATE book SET hidden = 1, hiddenUpdatedAt = 250 WHERE id = 'b1'")
            it.execSQL("INSERT INTO book_file (path, bookId, source, folder, size, mtimeMs, lastSeenScan) VALUES ('/sdcard/a.epub', 'b1', 'scan', '/sdcard', 10, 5, 7)")
            it.execSQL("INSERT INTO bookmark (id, bookId, cfi, spineIndex, fraction, chapter, createdAt, updatedAt, deleted) VALUES ('m1', 'b1', 'c', 1, 0.5, 'One', 1, 2, 0)")
            it.execSQL("INSERT INTO annotation (id, bookId, cfi, spineIndex, text, chapter, color, style, note, createdAt, updatedAt, deleted) VALUES ('a1', 'b1', 'c', 1, 't', 'One', 'yellow', 'highlight', 'n', 1, 2, 0)")
        }.close()
        val migrated = helper.runMigrationsAndValidate(9)
        migrated.prepare("SELECT identityKey, title, addedAt, lastOpenedAt, hidden, hiddenUpdatedAt, updatedAt, deleted, separateOf, separatePath, separateMtimeMs FROM book").use {
            assertEquals(true, it.step())
            assertEquals("k", it.getText(0)); assertEquals("WS Book", it.getText(1))
            assertEquals(listOf(100L, 200L, 1L, 250L, 200L, 0L), (2..7).map(it::getLong))
            assertEquals(listOf(true, true, true), (8..10).map(it::isNull))
            assertEquals(false, it.step())
        }
        migrated.prepare("SELECT path, source, size, mtimeMs, lastSeenScan FROM book_file WHERE bookId = 'b1' ORDER BY path").use {
            assertEquals(true, it.step()); assertEquals("/sdcard/a.epub", it.getText(0)); assertEquals("scan", it.getText(1)); assertEquals(listOf(10L, 5L, 7L), (2..4).map(it::getLong))
            assertEquals(true, it.step()); assertEquals("opened", it.getText(1))
        }
        migrated.prepare("SELECT cfi FROM reading_position WHERE bookId = 'b1'").use { assertEquals(true, it.step()); assertEquals("epubcfi(/6/4!/4/2/1:0)", it.getText(0)) }
        migrated.prepare("SELECT (SELECT COUNT(*) FROM bookmark WHERE bookId = 'b1'), (SELECT note FROM annotation WHERE id = 'a1')").use { assertEquals(true, it.step()); assertEquals(1L, it.getLong(0)); assertEquals("n", it.getText(1)) }
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
