package app.fernreader.core.library

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.fernreader.core.data.AppDatabase
import app.fernreader.core.data.HybridClock
import app.fernreader.core.data.LibraryRepository
import app.fernreader.core.data.MigrationTest
import app.fernreader.core.data.buildDatabase
import app.fernreader.core.data.jvmDatabaseBuilder
import app.fernreader.core.epub.meta.BookIdentity
import app.fernreader.core.epub.openFileSource
import app.fernreader.core.epub.zip.ZipReader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The user-data requirement of M3 (plan Review Focus 1): a book read in M2 through the system picker (a content://
 * row, identity from foliate's whitespace-collapsed identifier) meets the first scan after the upgrade to schema v3.
 * It must stay one library entry with the same id and the same reading position.
 */
class M2LibraryMigrationTest {
    @Test fun picker_book_from_m2_merges_with_its_scanned_file() = runBlocking {
        val root = Files.createTempDirectory("storage").toFile()
        val file = File(root, "Download/ws.epub").apply { parentFile.mkdirs() }
        // The OPF has newlines and tabs inside the identifier; foliate reported it collapsed in M2.
        file.writeBytes(ScanFixtures.epub("\n    urn:uuid:ws-m2 \t\n    edition  ", "WS Book"))
        val digest = openFileSource(file.path).use { ZipReader.open(it).centralDirectoryDigest }
        val m2Identity = BookIdentity.of("urn:uuid:ws-m2 edition", digest)

        val dbPath = Files.createTempDirectory("m2").resolve("app.db")
        MigrationTestHelper(Path.of("schemas"), dbPath, BundledSQLiteDriver(), AppDatabase::class)
            .createDatabase(2).also { MigrationTest.insertM2Book(it, identityKey = m2Identity) }.close()

        val db = buildDatabase(jvmDatabaseBuilder(dbPath.toString())) // AutoMigration 2 → 3 on first use
        val clock = HybridClock(System::currentTimeMillis)
        val repo = LibraryRepository(db, clock)
        Scanner(db, clock, FileStorageWalker(root.path)).scan(ScanScope.Everywhere)

        val books = repo.library().first()
        assertEquals(listOf("b1"), books.map { it.id })
        assertEquals(setOf(file.path, "content://com.android.providers.downloads.documents/document/42"), books.single().paths.toSet())
        assertEquals(file.path, books.single().path) // the scanned file is preferred for opening
        val pos = repo.position("b1")!!
        assertEquals("epubcfi(/6/4!/4/2/1:0)", pos.cfi); assertEquals(300L, pos.updatedAt)
        assertEquals(200L, db.libraryDao().byId("b1")!!.lastOpenedAt) // still at the top of "recently read"
        db.close()
    }
}
