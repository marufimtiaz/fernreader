package app.epubreader.library

import org.junit.Test
import org.junit.Assert.assertEquals

/** M3 deferred: thumbnails are versioned per extraction (no stale cover from an image cache); unreferenced ones go. */
class ThumbFilesTest {
    @Test fun names_change_per_extraction_and_orphans_are_found() {
        val a = ThumbFiles.name("book-1", 100); val b = ThumbFiles.name("book-1", 200)
        assertEquals("book-1-100.webp", a); assertEquals("book-1-200.webp", b)
        val dir = listOf("/t/book-1-100.webp", "/t/book-1-200.webp", "/t/book-2-5.webp", "/t/book-3.webp", "/t/x.tmp")
        assertEquals(listOf("/t/book-1-100.webp", "/t/book-3.webp", "/t/x.tmp"), ThumbFiles.orphans(dir, referenced = setOf("/t/book-1-200.webp", "/t/book-2-5.webp")))
    }
}
