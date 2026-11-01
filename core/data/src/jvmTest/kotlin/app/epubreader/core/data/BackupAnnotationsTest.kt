package app.epubreader.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F-090 / C-041 (M6): bookmarks and annotations (highlights, underlines, notes) in backup format version 1, with their
 * tombstones; restore merges per row id (C-011: the newer `updatedAt` wins, equal keeps the local row).
 */
class BackupAnnotationsTest {
    private class Device {
        val clock = HybridClock(System::currentTimeMillis)
        val db = testDb()
        val repo = LibraryRepository(db, clock)
        val bookmarks = BookmarkStore(db, clock)
        val annotations = AnnotationStore(db, clock)
        fun backup() = Backup(db, clock)
        suspend fun open(key: String, title: String) =
            repo.recordOpened(OpenedBookInfo(key, title, listOf("Author"), SourceRef.File("/s/$key.epub"), 1)).bookId
        suspend fun allBookmarks() = db.backupDao().bookmarks().sortedBy { it.id }
        suspend fun allAnnotations() = db.backupDao().annotations().sortedBy { it.id }
    }

    private val a = Device()

    @Test fun export_has_every_column_and_the_tombstones() = runTest {
        val alice = a.open("k-alice", "Alice")
        val bm = a.bookmarks.add(alice, "epubcfi(/6/4!/4/2/1:7)", 1, 0.1, "One")
        val gone = a.bookmarks.add(alice, "epubcfi(/6/6!/4/2/1:7)", 2, 0.2, "Two"); a.bookmarks.remove(gone.id)
        val h = a.annotations.add(alice, "epubcfi(/6/4!/4/2,/1:0,/1:5)", 1, "Hello", "One", HighlightColors.BLUE, AnnotationStyle.UNDERLINE)
        a.annotations.update(h.id, note = "a note")
        val o = Json.parseToJsonElement(a.backup().export("t")).jsonObject
        assertTrue("highlights" !in o) // the M7 placeholder key is now "annotations"
        val bms = o["bookmarks"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf(bm.id to false, gone.id to true), bms.map { it["id"]!!.jsonPrimitive.content to it["deleted"]!!.jsonPrimitive.content.toBoolean() }.toSet())
        val an = o["annotations"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("id", "bookId", "cfi", "spineIndex", "text", "chapter", "color", "style", "note", "createdAt", "updatedAt", "deleted"), an.keys)
        assertEquals("a note", an["note"]!!.jsonPrimitive.content)
        assertEquals("underline", an["style"]!!.jsonPrimitive.content)
    }

    @Test fun round_trip_export_clear_rescan_restore() = runTest {
        val alice = a.open("k-alice", "Alice")
        a.bookmarks.add(alice, "epubcfi(/6/4!/4/2/1:7)", 1, 0.1, "One")
        val gone = a.bookmarks.add(alice, "epubcfi(/6/6!/4/2/1:7)", 2, 0.2, "Two"); a.bookmarks.remove(gone.id)
        a.annotations.add(alice, "epubcfi(/6/4!/4/2,/1:0,/1:5)", 1, "Hello", "One", HighlightColors.GREEN, AnnotationStyle.HIGHLIGHT)
        val n = a.annotations.add(alice, "epubcfi(/6/6!/4/2,/1:0,/1:5)", 2, "World", "Two", HighlightColors.PINK, AnnotationStyle.UNDERLINE)
        a.annotations.update(n.id, note = "line 1\nline 2")
        val removed = a.annotations.add(alice, "epubcfi(/6/8!/4/2,/1:0,/1:5)", 3, "Gone", "Three", HighlightColors.YELLOW, AnnotationStyle.HIGHLIGHT)
        a.annotations.remove(removed.id)
        val json = a.backup().export("t")

        val d = Device(); val alice2 = d.open("k-alice", "Alice") // new book id after "clear app data" + rescan
        d.backup().restore(json)
        assertEquals(a.allBookmarks().map { it.copy(bookId = alice2) }, d.allBookmarks())
        assertEquals(a.allAnnotations().map { it.copy(bookId = alice2) }, d.allAnnotations())
        assertEquals(1, d.bookmarks.bookmarks(alice2).first().size)
        assertEquals(listOf("Hello", "World"), d.annotations.annotations(alice2).first().map { it.text })
        assertEquals("line 1\nline 2", d.annotations.annotations(alice2).first()[1].note)
    }

    @Test fun newer_change_wins_both_ways_and_equal_keeps_local() = runTest {
        val alice = a.open("k-alice", "Alice")
        val h = a.annotations.add(alice, "epubcfi(/6/4!/4/2,/1:0,/1:5)", 1, "Hello", "One", HighlightColors.GREEN, AnnotationStyle.HIGHLIGHT)
        val bm = a.bookmarks.add(alice, "epubcfi(/6/4!/4/2/1:7)", 1, 0.1, "One")
        val older = a.backup().export("t")
        a.annotations.update(h.id, color = HighlightColors.BLUE, note = "later") // local change after the backup
        a.backup().restore(older)
        assertEquals(HighlightColors.BLUE, a.annotations.annotations(alice).first().single().color) // not undone
        assertEquals("later", a.annotations.annotations(alice).first().single().note)

        // an older local row is replaced by the newer backup
        val d = Device(); val alice2 = d.open("k-alice", "Alice")
        d.backup().restore(older)
        assertEquals(HighlightColors.GREEN, d.annotations.annotations(alice2).first().single().color)
        d.backup().restore(a.backup().export("t"))
        assertEquals(HighlightColors.BLUE, d.annotations.annotations(alice2).first().single().color)
        assertEquals("later", d.annotations.annotations(alice2).first().single().note)

        // equal stamps: the local row stays
        val local = d.db.bookmarkDao().anyRow(bm.id)!!
        d.db.bookmarkDao().upsert(local.copy(chapter = "Local chapter"))
        d.backup().restore(a.backup().export("t"))
        assertEquals("Local chapter", d.db.bookmarkDao().anyRow(bm.id)!!.chapter)
    }

    @Test fun a_newer_tombstone_deletes_an_older_one_is_ignored() = runTest {
        val alice = a.open("k-alice", "Alice")
        val bm = a.bookmarks.add(alice, "epubcfi(/6/4!/4/2/1:7)", 1, 0.1, "One")
        val h = a.annotations.add(alice, "epubcfi(/6/4!/4/2,/1:0,/1:5)", 1, "Hello", "One", HighlightColors.GREEN, AnnotationStyle.HIGHLIGHT)
        val live = a.backup().export("t")
        val d = Device(); val alice2 = d.open("k-alice", "Alice")
        d.backup().restore(live)
        assertEquals(1, d.bookmarks.bookmarks(alice2).first().size)

        a.bookmarks.remove(bm.id); a.annotations.remove(h.id)
        val tombstones = a.backup().export("t")
        d.backup().restore(tombstones) // newer tombstones delete
        assertTrue(d.bookmarks.bookmarks(alice2).first().isEmpty())
        assertTrue(d.annotations.annotations(alice2).first().isEmpty())

        // a later local undo (same row, newer stamp) is not deleted again by the older tombstone
        d.annotations.restore(h.id, note = "kept")
        d.backup().restore(tombstones)
        assertEquals("kept", d.annotations.annotations(alice2).first().single().note)
        // and an older live copy does not bring back a newer local removal
        a.backup().restore(live)
        assertTrue(a.bookmarks.bookmarks(alice).first().isEmpty())
        assertTrue(a.annotations.annotations(alice).first().isEmpty())
    }

    /** Same rule as positions (M7): rows of a book that is not on this device wait on its pending identity. */
    @Test fun rows_of_a_book_not_on_the_device_attach_when_it_is_found() = runTest {
        val frank = a.open("k-frank", "Frankenstein")
        a.bookmarks.add(frank, "epubcfi(/6/4!/4/2/1:7)", 1, 0.1, "One")
        a.annotations.add(frank, "epubcfi(/6/4!/4/2,/1:0,/1:5)", 1, "Hello", "One", HighlightColors.GREEN, AnnotationStyle.HIGHLIGHT)
        val d = Device()
        d.backup().restore(a.backup().export("t"))
        assertTrue(d.repo.library().first().isEmpty())
        val found = d.open("k-frank", "Frankenstein")
        assertEquals(1, d.bookmarks.bookmarks(found).first().size)
        assertEquals("Hello", d.annotations.annotations(found).first().single().text)
    }

    @Test fun rows_whose_book_is_not_in_the_file_are_skipped() = runTest {
        val alice = a.open("k-alice", "Alice")
        a.bookmarks.add(alice, "epubcfi(/6/4!/4/2/1:7)", 1, 0.1, "One")
        val json = a.backup().export("t")
        val o = Json.parseToJsonElement(json).jsonObject
        val noBooks = kotlinx.serialization.json.JsonObject(o + ("books" to kotlinx.serialization.json.JsonArray(emptyList())))
        val d = Device()
        d.backup().restore(noBooks.toString())
        assertTrue(d.allBookmarks().isEmpty())
    }

    /** The open reader's lists are Room flows: a restore shows up without reopening the book. */
    @Test fun the_open_readers_lists_follow_a_restore() = runTest {
        val alice = a.open("k-alice", "Alice")
        a.bookmarks.add(alice, "epubcfi(/6/4!/4/2/1:7)", 1, 0.1, "One")
        a.annotations.add(alice, "epubcfi(/6/4!/4/2,/1:0,/1:5)", 1, "Hello", "One", HighlightColors.GREEN, AnnotationStyle.HIGHLIGHT)
        val json = a.backup().export("t")
        val d = Device(); val alice2 = d.open("k-alice", "Alice")
        val bookmarks = MutableStateFlow(-1); val annotations = MutableStateFlow(-1)
        backgroundScope.launch(Dispatchers.Default) { d.bookmarks.bookmarks(alice2).collect { bookmarks.value = it.size } }
        backgroundScope.launch(Dispatchers.Default) { d.annotations.annotations(alice2).collect { annotations.value = it.size } }
        suspend fun await(n: Int) = withContext(Dispatchers.Default) { withTimeout(5_000) { bookmarks.first { it == n }; annotations.first { it == n } } }
        await(0)
        d.backup().restore(json)
        await(1) // the same collectors, no re-subscription
    }

    @Test fun restored_stamps_seed_the_clock_and_are_bounded() = runTest {
        val alice = a.open("k-alice", "Alice")
        val bm = a.bookmarks.add(alice, "epubcfi(/6/4!/4/2/1:7)", 1, 0.1, "One")
        val far = Long.MAX_VALUE / 2
        a.db.bookmarkDao().upsert(a.db.bookmarkDao().anyRow(bm.id)!!.copy(updatedAt = far))
        val d = Device(); val alice2 = d.open("k-alice", "Alice")
        d.backup().restore(a.backup().export("t"))
        val restored = d.db.bookmarkDao().anyRow(bm.id)!!
        assertTrue(restored.updatedAt < System.currentTimeMillis() + 2 * 86_400_000L) // bounded to a day ahead
        val restarted = HybridClock(System::currentTimeMillis)
        seedClock(d.db, restarted)
        assertTrue(restarted.now() > restored.updatedAt) // a removal after a restart wins over the restored row
        BookmarkStore(d.db, restarted).remove(bm.id)
        assertTrue(d.bookmarks.bookmarks(alice2).first().isEmpty())
    }

    /** C-041 / R-009: a v1 file with bookmarks and annotations (written by M6) restores exactly, forever. Never edit it. */
    @Test fun frozen_v1_annotations_fixture_restores() = runTest {
        val json = javaClass.getResource("/backup/v1-annotations.json")!!.readText()
        val d = Device()
        val alice = d.open("4d1c9a2b7e3f5a60-0123456789abcdef0123456789abcdef", "Alice's Adventures in Wonderland")
        val r = d.backup().restore(json)
        assertEquals(1, r.booksMatched); assertEquals(1, r.booksWaiting)
        assertEquals(3, r.bookmarks); assertEquals(3, r.annotations) // tombstones and the waiting book's row included
        val other = d.db.libraryDao().byIdentity("9f00aa11bb22cc33-fedcba9876543210fedcba9876543210")!!.id

        assertEquals(listOf(
            BookmarkEntity("aaaaaaaa-0001-4000-8000-000000000001", alice, "epubcfi(/6/8!/4/2/10/1:120)", 3, 0.27, "Down the Rabbit-Hole", 1793410000000, 1793410000000, false),
            BookmarkEntity("aaaaaaaa-0002-4000-8000-000000000002", alice, "epubcfi(/6/12!/4/2/4/1:0)", 5, 0.41, "A Caucus-Race", 1793420000000, 1793430000000, true),
            BookmarkEntity("aaaaaaaa-0003-4000-8000-000000000003", other, "epubcfi(/6/4!/4/2/1:0)", 1, 0.02, "One", 1793440000000, 1793440000000, false),
        ), d.allBookmarks())
        assertEquals(listOf(
            AnnotationEntity("bbbbbbbb-0001-4000-8000-000000000001", alice, "epubcfi(/6/8!/4/2/12,/1:4,/1:52)", 3, "Curiouser and curiouser!", "The Pool of Tears",
                "green", "highlight", null, 1793450000000, 1793450000000, false),
            AnnotationEntity("bbbbbbbb-0002-4000-8000-000000000002", alice, "epubcfi(/6/10!/4/2/6,/1:0,/1:31)", 4, "We're all mad here.", "Pig and Pepper",
                "purple", "underline", "The Cat, on the madness of everyone.\nSecond line, «non-ASCII» é.", 1793460000000, 1793470000000, false),
            AnnotationEntity("bbbbbbbb-0003-4000-8000-000000000003", alice, "epubcfi(/6/14!/4/2/2,/1:0,/1:9)", 6, "Off with", "The Queen's Croquet-Ground",
                "pink", "highlight", null, 1793465000000, 1793480000000, true),
        ), d.allAnnotations())
        // what the reader shows: live rows only
        assertEquals(listOf("aaaaaaaa-0001-4000-8000-000000000001"), d.bookmarks.bookmarks(alice).first().map { it.id })
        assertEquals(listOf("Curiouser and curiouser!", "We're all mad here."), d.annotations.annotations(alice).first().map { it.text })
        assertEquals(1, Json.parseToJsonElement(json).jsonObject["version"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun an_m7_file_without_the_new_keys_restores_nothing_of_them() = runTest {
        val json = javaClass.getResource("/backup/v1.json")!!.readText()
        val d = Device(); d.open("4d1c9a2b7e3f5a60-0123456789abcdef0123456789abcdef", "Alice")
        val r = d.backup().restore(json) // has "bookmarks": [] and the old "highlights": [] placeholder
        assertEquals(0, r.bookmarks); assertEquals(0, r.annotations)
        val stripped = json.replace("\"bookmarks\": [],", "").replace("\"highlights\": [],", "")
        assertTrue("bookmarks" !in stripped)
        val e = Device(); e.open("4d1c9a2b7e3f5a60-0123456789abcdef0123456789abcdef", "Alice")
        e.backup().restore(stripped)
        assertTrue(e.allBookmarks().isEmpty()); assertNull(e.allAnnotations().firstOrNull())
    }
}
