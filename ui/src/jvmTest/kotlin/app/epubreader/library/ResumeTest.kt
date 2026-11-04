package app.epubreader.library

import app.epubreader.core.data.LibraryBook
import app.epubreader.core.data.SettingsPort
import app.epubreader.core.library.ScanState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** C-092: which book the library's Resume button offers, and when it shows. */
class ResumeTest {
    private fun book(id: String, added: Long = 1, opened: Long? = null, paths: List<String> = listOf("/b/$id.epub")) =
        LibraryBook(id, "Title $id", listOf("A"), "auto", null, added, opened, id, "a", paths.first(), paths)

    private object NoSettings : SettingsPort {
        override suspend fun get(key: String): String? = null
        override suspend fun put(key: String, value: String) = Unit
    }

    private fun state(books: List<LibraryBook>, scope: CoroutineScope, gone: Set<String> = emptySet()) =
        LibraryState(MutableStateFlow(books), MutableStateFlow(emptyList()), MutableStateFlow(ScanState()), NoSettings, {}, scope,
            exists = { it !in gone }, compute = Dispatchers.Unconfined)

    @Test fun no_history_offers_nothing() = runTest {
        val s = state(listOf(book("a"), book("b")), backgroundScope)
        runCurrent()
        assertNull(s.resume.value)
    }

    @Test fun offers_the_most_recently_read_book_not_the_newest_added() = runTest {
        val s = state(listOf(book("old", opened = 50), book("last", opened = 90), book("fresh", added = 200)), backgroundScope)
        runCurrent()
        assertEquals("last", s.resume.value?.id)
    }

    @Test fun the_same_book_in_every_view_and_search() = runTest {
        val last = book("last", opened = 90, paths = listOf("/x/last.epub"))
        val other = book("other", opened = 50, paths = listOf("/y/other.epub")).copy(collectionIds = listOf("c1"))
        val s = state(listOf(last, other), backgroundScope)
        for (v in listOf(LibraryView.Folders, LibraryView.Folder("/y"), LibraryView.Collection("c1"), LibraryView.Collection("empty"), LibraryView.All)) {
            s.openView(v); runCurrent()
            assertEquals("last", s.resume.value?.id, "$v")
            assertTrue(s.ui.value.showsResume, "$v")
        }
        s.setQuery("zzzz"); runCurrent()
        assertEquals("last", s.resume.value?.id)
    }

    @Test fun not_shown_while_selecting() = runTest {
        val s = state(listOf(book("a", opened = 5), book("b")), backgroundScope)
        runCurrent()
        assertTrue(s.ui.value.showsResume)
        s.startSelection("b"); runCurrent()
        assertFalse(s.ui.value.showsResume)
        s.clearSelection(); runCurrent()
        assertTrue(s.ui.value.showsResume)
    }

    @Test fun last_file_gone_offers_the_most_recent_book_that_still_exists() = runTest {
        val s = state(listOf(book("a", opened = 10), book("b", opened = 20), book("c", opened = 30)), backgroundScope, gone = setOf("/b/c.epub", "/b/b.epub"))
        runCurrent()
        assertEquals("a", s.resume.value?.id)
    }

    @Test fun every_read_file_gone_offers_nothing() = runTest {
        val s = state(listOf(book("a", opened = 10), book("unread")), backgroundScope, gone = setOf("/b/a.epub"))
        runCurrent()
        assertNull(s.resume.value)
    }

    /** F-018: a duplicate copy still opens the book. */
    @Test fun a_remaining_copy_keeps_the_book() = runTest {
        val s = state(listOf(book("a", opened = 10, paths = listOf("/b/one.epub", "/c/two.epub"))), backgroundScope, gone = setOf("/b/one.epub"))
        runCurrent()
        assertEquals("a", s.resume.value?.id)
    }

    @Test fun a_content_source_is_not_stat_checked() = runTest {
        val uri = "content://provider/doc/1"
        val s = state(listOf(book("a", opened = 10, paths = listOf(uri))), backgroundScope, gone = setOf(uri))
        runCurrent()
        assertEquals("a", s.resume.value?.id)
    }

    @Test fun a_file_deleted_later_is_noticed_on_the_next_check() = runTest {
        val gone = HashSet<String>()
        val s = LibraryState(MutableStateFlow(listOf(book("a", opened = 10), book("b", opened = 20))), MutableStateFlow(emptyList()), MutableStateFlow(ScanState()),
            NoSettings, {}, backgroundScope, exists = { it !in gone }, compute = Dispatchers.Unconfined)
        runCurrent()
        assertEquals("b", s.resume.value?.id)
        gone += "/b/b.epub"
        s.checkResume(); runCurrent()
        assertEquals("a", s.resume.value?.id)
    }

    /** The file vanished between the check and the tap: the F-018 dialog, and the button moves on. */
    @Test fun tapping_a_book_whose_file_just_went_shows_the_dialog_and_moves_on() = runTest {
        val gone = HashSet<String>()
        val s = LibraryState(MutableStateFlow(listOf(book("a", opened = 10), book("b", opened = 20))), MutableStateFlow(emptyList()), MutableStateFlow(ScanState()),
            NoSettings, {}, backgroundScope, exists = { it !in gone }, compute = Dispatchers.Unconfined)
        runCurrent()
        val tapped = s.resume.value!!
        gone += tapped.path
        s.open(tapped) { error("no file to open") }; runCurrent()
        assertEquals("b", s.ui.value.missing?.id)
        assertEquals("a", s.resume.value?.id)
    }

    @Test fun a_book_read_now_takes_over() = runTest {
        val books = MutableStateFlow(listOf(book("a", opened = 10), book("b", opened = 20)))
        val s = LibraryState(books, MutableStateFlow(emptyList()), MutableStateFlow(ScanState()), NoSettings, {}, backgroundScope, compute = Dispatchers.Unconfined)
        runCurrent()
        books.value = listOf(book("a", opened = 30), book("b", opened = 20)); runCurrent()
        assertEquals("a", s.resume.value?.id)
    }
}
