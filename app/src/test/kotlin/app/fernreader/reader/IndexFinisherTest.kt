package app.fernreader.reader

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class IndexFinisherTest {
    private val finished = mutableListOf<Pair<String, String>>()

    private fun finisher(
        ids: List<String>, paths: Map<String, String>, present: Set<String>,
        failOn: Set<String> = emptySet(),
    ) = IndexFinisher(
        incomplete = { ids }, filePath = { paths[it] }, exists = { it in present },
        finish = { id, path -> if (id in failOn) error("boom") else { finished += id to path } },
        warn = {},
    )

    @Test fun finishes_books_with_a_file_and_skips_the_rest() = runBlocking {
        val r = finisher(
            ids = listOf("a", "b", "c", "d"),
            paths = mapOf("a" to "/a.epub", "b" to "/b.epub", "c" to "/c.epub"), // d: no file row at all
            present = setOf("/a.epub", "/c.epub"), // b: file is gone
        ).run()
        assertEquals(listOf("a", "c"), r)
        assertEquals(listOf("a" to "/a.epub", "c" to "/c.epub"), finished)
    }

    @Test fun a_failing_book_does_not_stop_the_others() = runBlocking {
        val r = finisher(listOf("a", "b"), mapOf("a" to "/a", "b" to "/b"), setOf("/a", "/b"), failOn = setOf("a")).run()
        assertEquals(listOf("b"), r)
    }

    @Test fun nothing_incomplete_does_nothing() = runBlocking {
        assertEquals(emptyList<String>(), finisher(emptyList(), emptyMap(), emptySet()).run())
    }
}
