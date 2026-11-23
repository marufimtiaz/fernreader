package app.fernreader.core.search

import app.fernreader.core.epub.text.TextExtractor
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IndexBuilderTest {
    private fun tmp(name: String) = Files.createTempDirectory("idx").resolve(name).toString()
    private val words = listOf("alpha", "bravo", "charlie", "delta", "echo")
    /** 12 sections of 3 paragraphs each, long enough that every paragraph is its own segment. */
    private fun markup(n: Int = 12): List<String?> = (0 until n).map { i ->
        (0 until 3).joinToString("") { p -> "<p>Section $i paragraph $p: " + (words[(i + p) % 5] + " ").repeat(60) + "</p>" }
    }
    private fun book(n: Int = 12) = ListBookText(markup(n))

    @Test fun clean_build_indexes_every_section_and_is_complete() = runBlocking {
        val path = tmp("a.db")
        val b = book()
        IndexBuilder.build(path, "d1", b)
        val m = assertNotNull(SearchIndex.peek(path))
        assertEquals(IndexMeta(SearchIndex.formatTag(), "d1", 12, 12, true), m)
        assertEquals(36, rowCount(path).toInt())
        assertEquals((0 until 12).toList(), allRows(path).map { it.first }.distinct())
    }

    @Test fun rows_hold_exactly_the_segment_text_with_control_chars_blanked() = runBlocking {
        val path = tmp("a.db")
        val text = listOf("<p>one\u0001two\u0002three " + "x ".repeat(120) + "</p>")
        IndexBuilder.build(path, "d", ListBookText(text))
        val (spine, start, row) = allRows(path).single()
        assertEquals(0, spine); assertEquals(0, start)
        assertTrue(row.startsWith("one two three x"))
        assertFalse('\u0001' in row || '\u0002' in row)
    }

    @Test fun resume_after_kill() = runBlocking {
        val clean = tmp("clean.db"); IndexBuilder.build(clean, "d", book())
        val path = tmp("killed.db")
        lateinit var job: Job
        val killer = ListBookText(markup()) { if (it == 3) job.cancel() }
        job = launch(Dispatchers.IO) { IndexBuilder.build(path, "d", killer) }
        job.join()
        val mid = assertNotNull(SearchIndex.peek(path))
        assertEquals(4, mid.sectionsDone); assertFalse(mid.complete)
        assertEquals(12, rowCount(path)) // 4 sections x 3 rows, nothing half-written

        val resumed = ListBookText(markup())
        IndexBuilder.build(path, "d", resumed)
        assertEquals((4 until 12).toList(), resumed.reads) // earlier sections are not read again
        assertTrue(assertNotNull(SearchIndex.peek(path)).complete)
        assertEquals(rowCount(clean), rowCount(path))
        assertEquals(allRows(clean), allRows(path))
    }

    @Test fun a_book_closed_under_the_builder_aborts_and_stays_resumable() = runBlocking {
        val path = tmp("a.db")
        val src = markup()
        assertFailsWith<BookUnavailableException> {
            IndexBuilder.build(path, "d", ListBookText(src) { if (it == 5) throw BookUnavailableException("closed") })
        }
        val mid = assertNotNull(SearchIndex.peek(path))
        assertEquals(5, mid.sectionsDone); assertFalse(mid.complete)
        IndexBuilder.build(path, "d", ListBookText(src))
        assertEquals(36, rowCount(path).toInt())
    }

    @Test fun missing_section_counts_as_done_without_rows() = runBlocking {
        val path = tmp("a.db")
        IndexBuilder.build(path, "d", ListBookText(listOf(null, "<p>" + "word ".repeat(80) + "</p>", null)))
        val m = assertNotNull(SearchIndex.peek(path))
        assertTrue(m.complete); assertEquals(3, m.sectionsDone); assertEquals(1, rowCount(path).toInt())
    }

    @Test fun complete_only_at_end() = runBlocking {
        val path = tmp("a.db")
        val seen = mutableListOf<IndexProgress>()
        IndexBuilder.build(path, "d", book()) { seen += it }
        assertEquals((0..12).toList(), seen.map { it.sectionsDone }) // on open, then after each section
        assertTrue(seen.dropLast(1).none { it.complete })
        assertTrue(seen.last().complete)
    }

    @Test fun extractor_version_bump_rebuilds() = runBlocking {
        val path = tmp("a.db")
        IndexBuilder.build(path, "d", book(), tag = "${SearchIndex.VERSION}.${TextExtractor.VERSION - 1}")
        val again = book()
        IndexBuilder.build(path, "d", again)
        assertEquals((0 until 12).toList(), again.reads) // everything re-read
        assertEquals(SearchIndex.formatTag(), SearchIndex.peek(path)!!.tag)
        assertEquals(36, rowCount(path).toInt())
    }

    @Test fun digest_change_rebuilds() = runBlocking {
        val path = tmp("a.db")
        IndexBuilder.build(path, "old", book())
        val again = book()
        IndexBuilder.build(path, "new", again)
        assertEquals((0 until 12).toList(), again.reads)
        assertEquals("new", SearchIndex.peek(path)!!.digest)
        assertEquals(36, rowCount(path).toInt())
    }

    @Test fun complete_index_with_same_digest_is_left_alone() = runBlocking {
        val path = tmp("a.db")
        IndexBuilder.build(path, "d", book())
        val again = book()
        IndexBuilder.build(path, "d", again)
        assertEquals(emptyList(), again.reads)
    }

    @Test fun corrupt_file_is_replaced() = runBlocking {
        val path = tmp("a.db")
        java.io.File(path).writeBytes(ByteArray(5000) { 7 })
        IndexBuilder.build(path, "d", book())
        assertEquals(36, rowCount(path).toInt())
    }
}
