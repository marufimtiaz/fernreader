package app.epubreader.core.search

import java.nio.file.Files
import java.util.concurrent.Semaphore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SearchIndexesTest {
    private val dir = Files.createTempDirectory("searchidx").toString()
    private val indexes = SearchIndexes(dir, Dispatchers.IO)
    @AfterTest fun close() = indexes.close()

    /** Builds the index for [book] and waits until it is complete. */
    private fun built(id: String, book: BookText, digest: String = "d"): Long = measureTimeMillis {
        indexes.ensureBuilt(id, digest) { book }
        runBlocking { withTimeout(120_000) { indexes.progress(id).first { it.complete } } }
    }

    private fun search(id: String, text: String, whole: Boolean = false, cap: Int = 5000, digest: String = "d"): List<SearchUpdate> =
        runBlocking { withTimeout(60_000) { indexes.search(id, digest, SearchQuery(text, whole, cap)).toList() } }

    private fun List<SearchUpdate>.hits() = flatMap { it.hits }

    private val filler = "Nothing to see in this long ordinary sentence, it merely pads the paragraph out. ".repeat(4)
    /** A small War and Peace stand-in: diacritic and plain forms, a longer word sharing the prefix, apostrophes, hyphens, 6000 "the". */
    private val wpLike: ZipBookText by lazy {
        miniBook(listOf(
            "<h1>Book One</h1><p>$filler The battle of Borodino was fought. Borodinó again, and Borodino once more; $filler</p><p>Borodino $filler</p>",
            "<p>$filler The Borodinos are a family, unlike the field of Borodinó. $filler</p>",
            "<p>$filler Prince’s horse and the prince's sword. A well-known man, a well known fact, a Well-Known tale. $filler</p>",
            (0 until 3).joinToString("") { "<p>" + "the ".repeat(1000) + ". $filler</p>" },
            (0 until 3).joinToString("") { "<p>" + "the ".repeat(1000) + ". $filler</p>" },
        ))
    }

    @Test fun borodino_folds_diacritics_and_either_spelling_finds_both() {
        built("wp", wpLike)
        for (q in listOf("borodino", "Borodino", "Borodinó", "BORODINO")) {
            val hits = search("wp", q, whole = true).hits()
            assertEquals(5, hits.size, q) // 3+1 in section 0 (two plain, one accented, one more) ... and one in section 1
        }
    }

    @Test fun whole_word_excludes_longer_words_and_prefix_default_includes_them() {
        built("wp", wpLike)
        val whole = search("wp", "Borodino", whole = true).hits()
        val prefix = search("wp", "Borodino").hits()
        assertEquals(5, whole.size)
        assertEquals(6, prefix.size)
        val extra = prefix.filter { h -> whole.none { it.spineIndex == h.spineIndex && it.offset == h.offset } }.single()
        assertEquals("Borodinos", wpLike.section(extra.spineIndex)!!.text.substring(extra.offset, extra.offset + extra.length))
    }

    @Test fun hit_offsets_point_at_the_matched_text() {
        built("wp", wpLike)
        for (word in listOf("Borodino", "Borodinó")) for (h in search("wp", word, whole = true).hits()) {
            val text = wpLike.section(h.spineIndex)!!.text
            assertEquals("borodino", fold(text.substring(h.offset, h.offset + h.length)))
        }
    }

    @Test fun hit_carries_the_matched_text_for_the_jump() {
        built("wp", wpLike)
        for (h in search("wp", "Borodinó", whole = true).hits()) {
            val text = wpLike.section(h.spineIndex)!!.text
            assertEquals(text.substring(h.offset, h.offset + h.length), h.text)
            assertEquals(h.length, h.text.length)
        }
    }

    @Test fun snippet_contains_the_hit_at_the_stated_range() {
        built("wp", wpLike)
        for (h in search("wp", "borodino").hits()) {
            assertEquals(fold(wpLike.section(h.spineIndex)!!.text.substring(h.offset, h.offset + h.length)), fold(h.snippet.substring(h.snippetHitStart, h.snippetHitEnd)))
            assertTrue(h.snippet.length < 260, "${h.snippet.length}")
            assertFalse(h.snippet.contains("  ") || h.snippet.contains('\n'))
        }
    }

    @Test fun hits_arrive_in_book_order() {
        built("wp", wpLike)
        val hits = search("wp", "borodino").hits()
        assertEquals(hits.sortedWith(compareBy({ it.spineIndex }, { it.offset })), hits)
    }

    @Test fun phrase_with_hyphen_and_apostrophe_one_hit_per_occurrence() {
        built("wp", wpLike)
        val well = search("wp", "well known", whole = true).hits()
        assertEquals(3, well.size)
        assertEquals(listOf("well-known", "well known", "well-known"), well.map { h -> wpLike.section(h.spineIndex)!!.text.substring(h.offset, h.offset + h.length).lowercase() })
        assertEquals(2, search("wp", "prince's", whole = true).hits().size)
    }

    @Test fun cap_stops_at_5000_and_reports_capped() {
        built("wp", wpLike)
        val updates = search("wp", "the", whole = true)
        assertEquals(5000, updates.hits().size)
        val last = updates.last()
        assertTrue(last.capped && last.done && last.total == 5000)
        assertTrue(updates.dropLast(1).none { it.done || it.capped })
        assertEquals(5000, updates.dropLast(1).sumOf { it.hits.size } + last.hits.size)
        val small = search("wp", "the", whole = true, cap = 10)
        assertEquals(10, small.hits().size); assertTrue(small.last().capped)
    }

    @Test fun more_than_cap_hits_in_the_book_are_counted_without_cap() {
        built("wp", wpLike)
        val all = search("wp", "the", whole = true, cap = 100_000)
        val last = all.last()
        assertFalse(last.capped); assertTrue(last.done)
        assertTrue(last.total >= 6000, "${last.total}")
        assertEquals(last.total, all.hits().size)
    }

    @Test fun no_results_still_ends_with_done() {
        built("wp", wpLike)
        val u = search("wp", "zygote")
        assertEquals(listOf(SearchUpdate(emptyList(), 0, false, true)), u)
        assertEquals(listOf(SearchUpdate(emptyList(), 0, false, true)), search("wp", " -- "))
    }

    @Test fun search_during_an_incomplete_build_continues_as_sections_arrive() {
        val gate = Semaphore(0)
        val secs = (0 until 6).map { i ->
            "<p>" + (if (i == 1 || i == 4) "alpha " else "") + "pad ".repeat(80) + "</p>"
        }
        val book = ListBookText(secs) { if (it >= 2) gate.acquire() }
        indexes.ensureBuilt("gated", "d") { book }
        runBlocking {
            withTimeout(30_000) { indexes.progress("gated").first { it.sectionsDone >= 2 } }
            var released = false
            val updates = withTimeout(60_000) {
                indexes.search("gated", "d", SearchQuery("alpha", wholeWord = true)).onEach {
                    if (!released && it.hits.isNotEmpty()) { assertFalse(it.done); released = true; gate.release(10) }
                }.toList()
            }
            assertTrue(released)
            assertEquals(listOf(1, 4), updates.hits().map { it.spineIndex })
            assertTrue(updates.last().done && updates.last().total == 2)
            assertTrue(indexes.progress("gated").value.complete)
        }
    }

    @Test fun incomplete_and_not_running_index_ends_done_without_starting_a_build() {
        val book = ListBookText(List(4) { "<p>" + "alpha ".repeat(60) + "</p>" }) { if (it == 2) throw BookUnavailableException("closed") }
        indexes.ensureBuilt("half", "d") { book }
        runBlocking { withTimeout(30_000) { indexes.progress("half").first { it.failed } } }
        assertEquals(listOf("half"), indexes.incomplete())
        val u = search("half", "alpha", whole = true)
        assertTrue(u.last().done)
        assertEquals(setOf(0, 1), u.hits().map { it.spineIndex }.toSet()) // sections 0 and 1 had been indexed
        assertFalse(indexes.progress("half").value.running)
        // ensureBuilt again resumes (the book is back).
        built("half", ListBookText(List(4) { "<p>" + "alpha ".repeat(60) + "</p>" }))
        assertEquals(emptyList(), indexes.incomplete())
        assertEquals(setOf(0, 1, 2, 3), search("half", "alpha", whole = true).hits().map { it.spineIndex }.toSet())
    }

    private fun failing_build_ends_not_running(id: String, error: Throwable) {
        val secs = List(4) { "<p>" + "alpha ".repeat(60) + "</p>" }
        indexes.ensureBuilt(id, "d") { ListBookText(secs) { if (it == 2) throw error } }
        val updates = search(id, "alpha", whole = true) // may start before or after the failure: either way it must end
        assertTrue(updates.last().done)
        val p = runBlocking { withTimeout(30_000) { indexes.progress(id).first { !it.running } } }
        assertTrue(p.failed && !p.complete, "$p")
        assertEquals(listOf(id), indexes.incomplete())
        built(id, ListBookText(secs)) // resumable
    }

    @Test fun unexpected_exception_fails_the_build_without_hanging_a_search() =
        failing_build_ends_not_running("boom", IllegalStateException("boom"))

    @Test fun out_of_memory_fails_the_build_instead_of_skipping_the_section() =
        failing_build_ends_not_running("oom", OutOfMemoryError("test"))

    @Test fun stale_digest_finds_nothing_until_rebuilt() {
        built("wp", wpLike, digest = "d1")
        assertEquals(0, search("wp", "borodino", digest = "d2").hits().size)
        built2("wp", wpLike, "d2")
        assertEquals(6, search("wp", "borodino", digest = "d2").hits().size)
    }

    /** Waits for a rebuild under a new digest (progress.complete is already true from the old build). */
    private fun built2(id: String, book: BookText, digest: String) {
        indexes.ensureBuilt(id, digest) { book }
        runBlocking { withTimeout(60_000) { indexes.progress(id).first { it.complete && !it.running } } }
    }

    @Test fun a_search_makes_progress_while_the_single_build_thread_is_blocked_inside_a_section() {
        val buildThread = java.util.concurrent.Executors.newSingleThreadExecutor()
        val idx = SearchIndexes(Files.createTempDirectory("searchidx2").toString(), buildThread.asCoroutineDispatcher())
        val gate = Semaphore(0)
        try {
            val secs = (0 until 4).map { i -> "<p>" + (if (i == 1) "alpha " else "") + "pad ".repeat(80) + "</p>" }
            idx.ensureBuilt("blocked", "d") { ListBookText(secs) { if (it >= 2) gate.acquire() } }
            runBlocking {
                withTimeout(30_000) { idx.progress("blocked").first { it.sectionsDone >= 2 } }
                // the build thread now sits in section 2; the query must still run (on its own dispatcher)
                val first = withTimeout(10_000) { idx.search("blocked", "d", SearchQuery("alpha", wholeWord = true)).first() }
                assertEquals(listOf(1), first.hits.map { it.spineIndex })
            }
        } finally {
            gate.release(10); idx.close(); buildThread.shutdownNow()
        }
    }

    @Test fun ensureBuilt_is_idempotent_and_one_build_at_a_time() {
        val gate = Semaphore(0)
        val book = ListBookText(List(3) { "<p>" + "pad ".repeat(80) + "</p>" }) { gate.acquire() }
        indexes.ensureBuilt("b", "d") { book }
        indexes.ensureBuilt("b", "d") { error("second build must not start") }
        gate.release(10)
        runBlocking { withTimeout(30_000) { indexes.progress("b").first { it.complete } } }
        assertEquals(listOf(0, 1, 2), book.reads)
    }

    @Test fun delete_removes_the_index() {
        built("wp", wpLike)
        runBlocking { indexes.delete("wp") }
        assertEquals(emptyList(), indexes.incomplete())
        assertFalse(java.io.File(dir, "wp.db").exists())
        assertFalse(indexes.progress("wp").value.complete)
        assertEquals(0, search("wp", "borodino").hits().size)
    }

    // ---- generated stress books and the full corpus ----

    @Test fun many_chapters_marker_is_one_hit_in_chapter_1777() {
        val book = openBook(Corpus.generated("stress-2000-chapters.epub"))
        val ms = built("many", book)
        println("BENCH 2000-chapter build ms=$ms sections=${book.sectionCount}")
        val hits = search("many", "MARKERWORDMANY").hits()
        assertEquals(1, hits.size)
        val text = book.section(hits.single().spineIndex)!!.text
        assertTrue("Chapter 1777" in text.take(40), text.take(40))
    }

    @Test fun huge_single_chapter_marker_has_138_hits() {
        val book = openBook(Corpus.generated("stress-huge-single-chapter.epub"))
        val ms = built("huge", book)
        println("BENCH huge-chapter build ms=$ms")
        val t = measureTimeMillis { assertEquals(138, search("huge", "MARKERWORDHUGE").hits().size) }
        println("BENCH huge-chapter query ms=$t")
    }

    @Test fun war_and_peace_borodino_108_hits_and_timings() {
        val file = Corpus.full("gutenberg/war-and-peace-epub3.epub") ?: return println("SKIP war and peace: full corpus absent")
        val book = openBook(file)
        val ms = built("wp-real", book)
        println("BENCH war-and-peace build ms=$ms sections=${book.sectionCount}")
        for (q in listOf("Borodinó", "borodino")) {
            var first = -1L
            val t0 = System.nanoTime()
            val updates = runBlocking { indexes.search("wp-real", "d", SearchQuery(q)).onEach { if (first < 0) first = (System.nanoTime() - t0) / 1_000_000 }.toList() }
            println("BENCH war-and-peace '$q' first ms=$first done ms=${(System.nanoTime() - t0) / 1_000_000} hits=${updates.hits().size}")
            assertEquals(108, updates.hits().size, q)
        }
        for (q in listOf("the", "zygote")) {
            val t0 = System.nanoTime()
            val u = runBlocking { indexes.search("wp-real", "d", SearchQuery(q)).toList() }
            println("BENCH war-and-peace '$q' done ms=${(System.nanoTime() - t0) / 1_000_000} hits=${u.hits().size} capped=${u.last().capped}")
        }
    }
}
