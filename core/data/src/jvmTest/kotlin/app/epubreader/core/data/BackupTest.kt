package app.epubreader.core.data

import androidx.room.useWriterConnection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-090 backup and restore: R-009 versioned format, C-011 merge (newest wins), tombstones, pending identities. */
class BackupTest {
    private val clock = HybridClock(System::currentTimeMillis)
    private val dbA = testDb()
    private val a = LibraryRepository(dbA, clock)

    private suspend fun open(repo: LibraryRepository, key: String, title: String) =
        repo.recordOpened(OpenedBookInfo(key, title, listOf("Author"), SourceRef.File("/s/$key.epub"), 1)).bookId

    /** A fresh device: empty database, its own clock (as after "clear app data"). */
    private class Device {
        val clock = HybridClock(System::currentTimeMillis)
        val db = testDb()
        val repo = LibraryRepository(db, clock)
        fun backup(folderExists: (String) -> Boolean = { true }) = Backup(db, clock, folderExists = folderExists)
    }

    @Test fun backup_clear_rescan_restore_brings_everything_back() = runTest {
        val alice = open(a, "k-alice", "Alice"); val frank = open(a, "k-frank", "Frankenstein"); val moby = open(a, "k-moby", "Moby-Dick")
        dbA.positionDao().put(alice, "epubcfi(/6/4!/4/2/1:7)", 0.3, 1, clock.now())
        val c = a.createCollection("Classics"); a.addToCollection(c, listOf(alice, frank))
        a.hide(listOf(moby))
        SettingsStore(dbA, clock).put(SettingsStore.LIBRARY_SORT, "TITLE")
        val json = Backup(dbA, clock).export(appVersion = "test")

        val d = Device()
        val alice2 = open(d.repo, "k-alice", "Alice"); val frank2 = open(d.repo, "k-frank", "Frankenstein"); open(d.repo, "k-moby", "Moby-Dick") // the rescan
        val r = d.backup().restore(json)
        assertEquals(3, r.booksMatched); assertEquals(0, r.booksWaiting)
        assertEquals("epubcfi(/6/4!/4/2/1:7)", d.repo.position(alice2)!!.cfi)
        assertEquals(listOf("Classics"), d.repo.collections().first().map { it.name })
        val cid = d.repo.collections().first().single().id
        assertEquals(setOf(alice2, frank2), d.repo.library().first().filter { cid in it.collectionIds }.map { it.id }.toSet())
        assertEquals(listOf("Moby-Dick"), d.repo.hiddenBooks().first().map { it.title }) // hidden although the rescan touched the row later
        assertEquals("TITLE", SettingsStore(d.db, d.clock).get(SettingsStore.LIBRARY_SORT))
    }

    @Test fun the_file_has_the_versioned_envelope_and_no_book_files() = runTest {
        open(a, "k-alice", "Alice")
        val o = Json.parseToJsonElement(Backup(dbA, clock).export(appVersion = "1.0")).jsonObject
        assertEquals("app.epubreader.backup", o["format"]!!.jsonPrimitive.content)
        assertEquals(1, o["version"]!!.jsonPrimitive.content.toInt())
        for (k in listOf("books", "positions", "bookmarks", "annotations", "collections", "collectionBooks", "settings")) assertTrue(k in o, k)
        assertEquals("k-alice", o["books"]!!.jsonArray.single().jsonObject["identityKey"]!!.jsonPrimitive.content)
        assertTrue("path" !in o["books"]!!.jsonArray.single().jsonObject) // file locations are device-local
    }

    @Test fun newest_change_wins_both_ways() = runTest {
        val alice = open(a, "k-alice", "Alice")
        dbA.positionDao().put(alice, "epubcfi(/6/2!/4/2/1:1)", 0.1, 1, 1_000)
        val c = a.createCollection("Old name")
        val json = Backup(dbA, clock).export("t")
        // local edits after the backup: the backup must not undo them
        dbA.positionDao().put(alice, "epubcfi(/6/8!/4/2/1:1)", 0.8, 4, 2_000_000_000_000)
        a.renameCollection(c, "New name")
        Backup(dbA, clock).restore(json)
        assertEquals("epubcfi(/6/8!/4/2/1:1)", a.position(alice)!!.cfi)
        assertEquals("New name", a.collections().first().single().name)
        // and an older local state is replaced by the newer backup
        val d = Device(); val alice2 = open(d.repo, "k-alice", "Alice")
        d.db.positionDao().put(alice2, "epubcfi(/6/2!/4/2/1:0)", 0.0, 1, 500)
        d.backup().restore(Backup(dbA, clock).export("t"))
        assertEquals("epubcfi(/6/8!/4/2/1:1)", d.repo.position(alice2)!!.cfi)
    }

    @Test fun tombstones_are_respected() = runTest {
        val alice = open(a, "k-alice", "Alice")
        val c = a.createCollection("Gone"); a.addToCollection(c, listOf(alice))
        val before = Backup(dbA, clock).export("t") // collection alive here
        a.deleteCollection(c)
        Backup(dbA, clock).restore(before) // the older live copy does not bring it back
        assertTrue(a.collections().first().isEmpty())
        // a newer tombstone in a backup deletes the local collection
        val d = Device(); open(d.repo, "k-alice", "Alice")
        d.backup().restore(before)
        assertEquals(1, d.repo.collections().first().size)
        d.backup().restore(Backup(dbA, clock).export("t"))
        assertTrue(d.repo.collections().first().isEmpty())
    }

    @Test fun books_not_on_the_device_wait_and_attach_when_found() = runTest {
        val frank = open(a, "k-frank", "Frankenstein")
        dbA.positionDao().put(frank, "epubcfi(/6/10!/4/2/1:3)", 0.5, 5, clock.now())
        val c = a.createCollection("Later"); a.addToCollection(c, listOf(frank))
        val d = Device()
        val r = d.backup().restore(Backup(dbA, clock).export("t"))
        assertEquals(0, r.booksMatched); assertEquals(1, r.booksWaiting)
        assertTrue(d.repo.library().first().isEmpty()) // nothing to open yet
        val found = open(d.repo, "k-frank", "Frankenstein") // the scan or an open-with finds the file later
        assertEquals("epubcfi(/6/10!/4/2/1:3)", d.repo.position(found)!!.cfi)
        assertEquals(listOf(found), d.repo.library().first().filter { it.collectionIds.isNotEmpty() }.map { it.id })
    }

    @Test fun same_named_collection_on_this_device_is_merged_not_doubled() = runTest {
        val alice = open(a, "k-alice", "Alice")
        val c = a.createCollection("Classics"); a.addToCollection(c, listOf(alice))
        val d = Device(); val alice2 = open(d.repo, "k-alice", "Alice"); val moby2 = open(d.repo, "k-moby", "Moby")
        val local = d.repo.createCollection("classics"); d.repo.addToCollection(local, listOf(moby2))
        d.backup().restore(Backup(dbA, clock).export("t"))
        assertEquals(1, d.repo.collections().first().size)
        assertEquals(setOf(alice2, moby2), d.repo.library().first().filter { it.collectionIds.isNotEmpty() }.map { it.id }.toSet())
    }

    @Test fun discovery_folder_is_restored_only_if_it_exists_here() = runTest {
        SettingsStore(dbA, clock).put(SettingsStore.LIBRARY_SCOPE, "/storage/emulated/0/Books")
        val json = Backup(dbA, clock).export("t")
        val d = Device()
        d.backup(folderExists = { false }).restore(json)
        assertNull(SettingsStore(d.db, d.clock).get(SettingsStore.LIBRARY_SCOPE))
        d.backup(folderExists = { true }).restore(json)
        assertEquals("/storage/emulated/0/Books", SettingsStore(d.db, d.clock).get(SettingsStore.LIBRARY_SCOPE))
    }

    @Test fun later_local_changes_win_over_restored_future_stamps() = runTest {
        val alice = open(a, "k-alice", "Alice")
        dbA.positionDao().put(alice, "epubcfi(/6/2!/4/2/1:1)", 0.1, 1, 4_000_000_000_000) // a clock far ahead (other device)
        val d = Device(); val alice2 = open(d.repo, "k-alice", "Alice")
        d.backup().restore(Backup(dbA, clock).export("t"))
        assertTrue(d.db.positionDao().put(alice2, "epubcfi(/6/4!/4/2/1:1)", 0.2, 2, d.clock.now())) // the next page turn is stored
        assertEquals("epubcfi(/6/4!/4/2/1:1)", d.repo.position(alice2)!!.cfi)
    }

    @Test fun rejects_other_files_and_newer_versions() = runTest {
        val b = Backup(dbA, clock)
        assertFailsWith<BackupException.NotABackup> { b.restore("{\"hello\": 1}") }
        assertFailsWith<BackupException.NotABackup> { b.restore("not json") }
        assertFailsWith<BackupException.TooNew> { b.restore("{\"format\":\"app.epubreader.backup\",\"version\":99}") }
    }

    /** R-009: a v1 file written by M7 must restore in every later version. Never edit the fixture; add migrations. */
    @Test fun frozen_v1_fixture_restores() = runTest {
        val json = javaClass.getResource("/backup/v1.json")!!.readText()
        val d = Device()
        val alice = open(d.repo, "4d1c9a2b7e3f5a60-0123456789abcdef0123456789abcdef", "Alice's Adventures in Wonderland")
        val r = d.backup().restore(json)
        assertEquals(1, r.booksMatched); assertEquals(1, r.booksWaiting)
        assertEquals("epubcfi(/6/8!/4/2/10/1:120)", d.repo.position(alice)!!.cfi)
        assertEquals(listOf("Classics"), d.repo.collections().first().map { it.name })
        assertEquals(listOf(alice), d.repo.library().first().filter { it.collectionIds.isNotEmpty() }.map { it.id })
        assertEquals("LIST", SettingsStore(d.db, d.clock).get(SettingsStore.LIBRARY_LAYOUT))
        val o: JsonObject = Json.parseToJsonElement(json).jsonObject
        assertEquals(1, o["version"]!!.jsonPrimitive.content.toInt())
    }

    /** Final review I-2: a book whose file changed in place keeps its id (C-009); an older backup must not take its position. */
    @Test fun restoring_an_older_backup_after_an_in_place_change_keeps_the_live_position() = runTest {
        val x = open(a, "k1-old", "Book X")
        dbA.positionDao().put(x, "epubcfi(/6/4!/4/2/1:1)", 0.1, 1, clock.now())
        val older = Backup(dbA, clock).export("t")
        dbA.useWriterConnection { t -> t.usePrepared("UPDATE book SET identityKey = 'k2-new' WHERE id = '$x'") { it.step() } } // the scanner's in-place identity change (same id)
        dbA.positionDao().put(x, "epubcfi(/6/6!/4/2/1:1)", 0.3, 2, clock.now())
        Backup(dbA, clock).restore(older)
        assertEquals("epubcfi(/6/6!/4/2/1:1)", a.position(x)!!.cfi) // the live book keeps its own (newer) position
        assertEquals(1, dbA.backupDao().books().size) // same stable id = same book: no pending duplicate
    }

    @Test fun a_position_id_already_used_by_another_book_is_not_taken_over() = runTest {
        val alice = open(a, "k-alice", "Alice")
        dbA.positionDao().put(alice, "epubcfi(/6/4!/4/2/1:1)", 0.1, 1, clock.now())
        val takenId = dbA.positionDao().get(alice)!!.id
        val d = Device(); val frank = open(d.repo, "k-frank", "Frank")
        d.db.positionDao().upsert(ReadingPositionEntity(takenId, frank, "epubcfi(/6/2!/4/2/1:0)", 0.0, 0, 1, false)) // same row id, other book
        val alice2 = open(d.repo, "k-alice", "Alice")
        d.backup().restore(Backup(dbA, clock).export("t"))
        assertEquals("epubcfi(/6/4!/4/2/1:1)", d.repo.position(alice2)!!.cfi)
        assertEquals("epubcfi(/6/2!/4/2/1:0)", d.repo.position(frank)!!.cfi)
    }

    /** Final review I-5: restored stamps outlive the process; foreign stamps are bounded. */
    @Test fun after_a_restart_local_changes_still_win_over_restored_stamps() = runTest {
        val ahead = System.currentTimeMillis() + 3_600_000 // another device whose clock runs an hour ahead
        SettingsStore(dbA, HybridClock { ahead }).put(SettingsStore.LIBRARY_SORT, "TITLE")
        val json = Backup(dbA, clock).export("t")
        val d = Device()
        d.backup().restore(json)
        val restarted = HybridClock(System::currentTimeMillis) // next process
        seedClock(d.db, restarted)
        SettingsStore(d.db, restarted).put(SettingsStore.LIBRARY_SORT, "AUTHOR") // the user's newer choice
        Backup(d.db, restarted).restore(json) // the same backup again
        assertEquals("AUTHOR", SettingsStore(d.db, restarted).get(SettingsStore.LIBRARY_SORT))
    }

    @Test fun absurd_future_stamps_are_bounded() = runTest {
        val json = Backup(dbA, clock).export("t").replace("\"settings\":[]", "\"settings\":[{\"id\":\"s9\",\"key\":\"library.sort\",\"json\":\"\\\"TITLE\\\"\",\"updatedAt\":${Long.MAX_VALUE},\"deleted\":false}]")
        val d = Device()
        d.backup().restore(json)
        assertEquals("TITLE", SettingsStore(d.db, d.clock).get(SettingsStore.LIBRARY_SORT))
        assertTrue(d.db.backupDao().settings().single().updatedAt < System.currentTimeMillis() + 2 * 86_400_000L)
        SettingsStore(d.db, d.clock).put(SettingsStore.LIBRARY_SORT, "AUTHOR")
        assertEquals("AUTHOR", SettingsStore(d.db, d.clock).get(SettingsStore.LIBRARY_SORT))
        assertTrue(d.clock.now() > 0) // no overflow
    }
}
