package app.epubreader.library

import app.epubreader.core.data.LibraryBook
import app.epubreader.core.data.ScanFailureEntity
import app.epubreader.core.data.SettingsPort
import app.epubreader.core.library.ScanEvent
import app.epubreader.core.library.ScanState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryStateTest {
    private fun book(id: String, title: String, authors: List<String>, added: Long, opened: Long? = null, dir: String = "auto") =
        LibraryBook(id, title, authors, dir, null, added, opened, title.lowercase().removePrefix("the "), authors.firstOrNull()?.lowercase() ?: "", "/b/$id.epub", listOf("/b/$id.epub"))

    private val books = listOf(
        book("moby", "Moby-Dick; or, The Whale", listOf("Herman Melville"), added = 1, opened = 50),
        book("alice", "Alice's Adventures in Wonderland", listOf("Lewis Carroll"), added = 2, opened = 90),
        book("frank", "Frankenstein", listOf("Mary Shelley"), added = 3),
        book("war", "War and Peace", listOf("Leo Tolstoy"), added = 4),
        book("he", "ספר הדקדוק", listOf("מחבר"), added = 5, dir = "rtl"),
        book("anon", "The Anonymous Book", emptyList(), added = 6),
    )

    private class FakeSettings : SettingsPort {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value }
    }

    private fun ids(q: String = "", s: LibrarySort = LibrarySort.RECENT) = LibraryState.arrange(books, q, s).map { it.id }

    @Test fun ville_finds_moby_dick() = assertEquals(listOf("moby"), ids("ville"))
    @Test fun whale_finds_by_title() = assertEquals(listOf("moby"), ids("WHALE"))
    @Test fun zzzz_is_empty() = assertEquals(emptyList(), ids("zzzz"))

    @Test fun each_sort_order() {
        assertEquals(listOf("alice", "moby", "anon", "he", "war", "frank"), ids(s = LibrarySort.RECENT)) // read first, then newest added
        assertEquals(listOf("anon", "he", "war", "frank", "alice", "moby"), ids(s = LibrarySort.ADDED))
        assertEquals(listOf("alice", "anon", "frank", "moby", "war", "he"), ids(s = LibrarySort.TITLE)) // case-insensitive, "The" ignored
        assertEquals(listOf("moby", "war", "alice", "frank", "he", "anon"), ids(s = LibrarySort.AUTHOR)) // by the author's sort key; no author last
    }

    @Test fun layout_and_sort_persist() = runTest {
        val settings = FakeSettings()
        val scan = MutableStateFlow(ScanState())
        val a = LibraryState(MutableStateFlow(books), MutableStateFlow(emptyList()), scan, settings, {}, backgroundScope)
        a.setLayout(LibraryLayout.LIST); a.setSort(LibrarySort.TITLE); runCurrent()
        val b = LibraryState(MutableStateFlow(books), MutableStateFlow(emptyList()), scan, settings, {}, backgroundScope)
        runCurrent()
        assertEquals(LibraryLayout.LIST, b.ui.value.layout); assertEquals(LibrarySort.TITLE, b.ui.value.sort)
    }

    @Test fun rtl_and_blank_metadata() {
        val he = books.single { it.id == "he" }
        assertEquals("rtl", he.titleDirection)
        assertTrue(books.single { it.id == "anon" }.authors.isEmpty()) // shown as the neutral placeholder
        assertEquals(listOf("he"), ids("הדקדוק"))
    }

    @Test fun matchRanges_case_insensitive() {
        assertEquals(listOf(7..11), matchRanges("Herman Melville", "MELVI"))
        assertEquals(listOf(0..1, 3..4), matchRanges("abxab", "AB"))
        assertEquals(emptyList(), matchRanges("abc", "  "))
    }

    @Test fun failure_notice_after_scan_until_dismissed() = runTest {
        val done = ScanEvent.Complete(1, 0, 1, 2, 5)
        val scan = MutableStateFlow(ScanState(lastComplete = done))
        val fails = MutableStateFlow(listOf(ScanFailureEntity("/d/fake.epub", "/d", "fake.epub", "NOT_A_ZIP", 19, 1, 1)))
        val s = LibraryState(MutableStateFlow(books), fails, scan, FakeSettings(), {}, backgroundScope)
        assertTrue(s.ui.first { it.loaded }.failureNoticeVisible)
        s.dismissFailures(); runCurrent()
        assertFalse(s.ui.value.failureNoticeVisible)
        scan.value = ScanState(lastComplete = done.copy(ms = 6)); runCurrent() // a new scan still finds it
        assertTrue(s.ui.value.failureNoticeVisible)
    }

    @Test fun missing_file_shows_dialog_and_remove_deletes() = runTest {
        val removed = mutableListOf<String>()
        val s = LibraryState(MutableStateFlow(books), MutableStateFlow(emptyList()), MutableStateFlow(ScanState()), FakeSettings(),
            { removed += it }, backgroundScope, exists = { false })
        var opened: String? = null
        s.open(books[0]) { opened = it }; runCurrent()
        assertEquals(null, opened); assertEquals("moby", s.ui.value.missing?.id)
        s.removeMissing(); runCurrent()
        assertEquals(listOf("moby"), removed); assertEquals(null, s.ui.value.missing)
    }

    @Test fun open_uses_a_remaining_duplicate() = runTest {
        val dup = books[0].copy(path = "/gone/a.epub", paths = listOf("/gone/a.epub", "/kept/b.epub"))
        val s = LibraryState(MutableStateFlow(books), MutableStateFlow(emptyList()), MutableStateFlow(ScanState()), FakeSettings(),
            {}, backgroundScope, exists = { it.startsWith("/kept") })
        var opened: String? = null
        s.open(dup) { opened = it }; runCurrent()
        assertEquals("/kept/b.epub", opened)
    }
}
