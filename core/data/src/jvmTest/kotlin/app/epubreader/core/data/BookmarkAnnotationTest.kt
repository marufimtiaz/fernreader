package app.epubreader.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Collects [flow] on real threads (Room queries run on IO) and lets a test wait for the latest emission to satisfy a condition. */
private class Collector<T>(scope: CoroutineScope, flow: Flow<List<T>>) {
    val seen = mutableListOf<List<T>>()
    private val job = scope.launch(Dispatchers.IO) { flow.collect { synchronized(seen) { seen += it } } }
    suspend fun await(cond: (List<T>) -> Boolean): List<T> = withContext(Dispatchers.Default) {
        withTimeout(5000) {
            while (true) { synchronized(seen) { seen.lastOrNull() }?.takeIf(cond)?.let { return@withTimeout it }; delay(10) }
            @Suppress("UNREACHABLE_CODE") emptyList()
        }
    }
    fun stop() = job.cancel()
}

class BookmarkAnnotationTest {
    private var now = 1000L
    private val db = testDb()
    private val clock = HybridClock { ++now }
    private val bookmarks = BookmarkStore(db, clock)
    private val annotations = AnnotationStore(db, clock)

    @Test fun bookmarks_are_per_book_in_book_order() = runTest {
        bookmarks.add("b1", "epubcfi(/6/8!/4/2/1:0)", 3, 0.5, "Three")
        bookmarks.add("b1", "epubcfi(/6/4!/4/10/1:0)", 1, 0.2, "One b")
        bookmarks.add("b1", "epubcfi(/6/4!/4/2/1:0)", 1, 0.1, "One a")
        bookmarks.add("b2", "epubcfi(/6/2!/4)", 0, 0.0, "")
        assertEquals(listOf("One a", "One b", "Three"), bookmarks.bookmarks("b1").first().map { it.chapter })
        assertEquals(1, bookmarks.bookmarks("b2").first().size)
    }

    @Test fun bookmark_fields_round_trip() = runTest {
        val b = bookmarks.add("b1", "epubcfi(/6/4!/4)", 1, 0.25, "Ch")
        assertEquals(Bookmark(b.id, "b1", "epubcfi(/6/4!/4)", 1, 0.25, "Ch", b.createdAt, b.createdAt), b)
        assertEquals(listOf(b), bookmarks.bookmarks("b1").first())
    }

    @Test fun removing_a_bookmark_leaves_a_tombstone() = runTest {
        val b = bookmarks.add("b1", "epubcfi(/6/4!/4)", 1, 0.25, "Ch")
        bookmarks.remove(b.id)
        assertEquals(emptyList(), bookmarks.bookmarks("b1").first())
        val row = db.bookmarkDao().anyRow(b.id)!!
        assertTrue(row.deleted)
        assertTrue(row.updatedAt > b.updatedAt)
    }

    @Test fun bookmarks_flow_updates_live() = runTest {
        val c = Collector(this, bookmarks.bookmarks("b1"))
        c.await { it.isEmpty() }
        val b = bookmarks.add("b1", "epubcfi(/6/4!/4)", 1, 0.25, "Ch")
        assertEquals(listOf(b.id), c.await { it.isNotEmpty() }.map { it.id })
        bookmarks.remove(b.id)
        c.await { it.isEmpty() && c.seen.size > 2 }
        c.stop()
    }

    @Test fun annotations_are_in_book_order_by_range_start() = runTest {
        annotations.add("b1", "epubcfi(/6/4!/4/2,/1:10,/1:20)", 1, "later", "Ch", "yellow", "highlight")
        annotations.add("b1", "epubcfi(/6/4!/4/2,/1:9,/1:12)", 1, "earlier", "Ch", "green", "underline")
        annotations.add("b1", "epubcfi(/6/2!/4/2,/1:0,/1:5)", 0, "first", "Ch", "blue", "highlight")
        assertEquals(listOf("first", "earlier", "later"), annotations.annotations("b1").first().map { it.text })
    }

    @Test fun equal_positions_tie_by_created_at() = runTest {
        val a = annotations.add("b1", "epubcfi(/6/4!/4/2,/1:1,/1:5)", 1, "a", "", "yellow", "highlight")
        val b = annotations.add("b1", "epubcfi(/6/4!/4/2,/1:1,/1:5)", 1, "b", "", "yellow", "highlight")
        assertEquals(listOf(a.id, b.id), annotations.annotations("b1").first().map { it.id })
    }

    @Test fun annotation_update_sets_given_fields_only() = runTest {
        val a = annotations.add("b1", "epubcfi(/6/4!/4/2,/1:1,/1:5)", 1, "t", "Ch", "yellow", "highlight")
        assertNull(a.note)
        annotations.update(a.id, note = "my note")
        var cur = annotations.annotations("b1").first().single()
        assertEquals("my note", cur.note)
        assertEquals("yellow", cur.color)
        assertEquals("highlight", cur.style)
        assertTrue(cur.updatedAt > a.updatedAt)
        annotations.update(a.id, color = "pink", style = "underline")
        cur = annotations.annotations("b1").first().single()
        assertEquals("pink", cur.color); assertEquals("underline", cur.style); assertEquals("my note", cur.note)
        annotations.update(a.id, note = "   ")
        assertNull(annotations.annotations("b1").first().single().note)
        annotations.update(a.id, note = "x")
        annotations.update(a.id, note = "")
        assertNull(annotations.annotations("b1").first().single().note)
    }

    @Test fun annotation_update_of_unknown_id_is_a_no_op() = runTest {
        annotations.update("nope", color = "pink")
        assertEquals(emptyList(), annotations.annotations("b1").first())
    }

    @Test fun removing_an_annotation_leaves_a_tombstone_and_flow_updates() = runTest {
        val c = Collector(this, annotations.annotations("b1"))
        c.await { it.isEmpty() }
        val a = annotations.add("b1", "epubcfi(/6/4!/4/2,/1:1,/1:5)", 1, "t", "Ch", "yellow", "highlight")
        assertEquals(listOf(a.id), c.await { it.isNotEmpty() }.map { it.id })
        annotations.remove(a.id)
        c.await { it.isEmpty() && c.seen.size > 2 }
        val row = db.annotationDao().anyRow(a.id)!!
        assertTrue(row.deleted); assertNotEquals(a.updatedAt, row.updatedAt)
        c.stop()
    }

    @Test fun palette_has_at_least_six_distinct_ids() {
        assertEquals(listOf("yellow", "green", "blue", "pink", "orange", "purple"), HighlightColors.ALL)
    }
}
