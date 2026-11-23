package app.fernreader.core.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchHistoryTest {
    private var now = 0L
    private fun store() = SearchHistoryStore(testDb(), HybridClock { ++now })

    @Test fun recent_is_newest_first_and_per_book() = runTest {
        val h = store()
        h.add("b1", "prince"); h.add("b1", "war"); h.add("b2", "other")
        assertEquals(listOf("war", "prince"), h.recent("b1"))
        assertEquals(listOf("other"), h.recent("b2"))
    }

    @Test fun searching_again_moves_it_to_the_top_without_a_duplicate() = runTest {
        val h = store()
        h.add("b1", "a"); h.add("b1", "b"); h.add("b1", "a")
        assertEquals(listOf("a", "b"), h.recent("b1"))
    }

    @Test fun recent_is_limited() = runTest {
        val h = store()
        repeat(30) { h.add("b1", "q$it") }
        assertEquals(20, h.recent("b1").size)
        assertEquals("q29", h.recent("b1").first())
        assertEquals(3, h.recent("b1", limit = 3).size)
    }

    @Test fun clear_removes_only_that_book() = runTest {
        val h = store()
        h.add("b1", "a"); h.add("b2", "a")
        h.clear("b1")
        assertEquals(emptyList(), h.recent("b1"))
        assertEquals(listOf("a"), h.recent("b2"))
    }
}
