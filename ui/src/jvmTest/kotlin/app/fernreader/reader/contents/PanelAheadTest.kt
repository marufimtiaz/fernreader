package app.fernreader.reader.contents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Contents panel is got ready ahead of showing, while nothing shows (ContentsSlide), so that its first frame on screen
 * only places it: when a preparation is due, what is kept, and what is disposed.
 */
class PanelAheadTest {
    private val disposed = mutableListOf<String>()
    private val a = PanelAhead<String> { disposed += it }

    @Test fun nothing_to_get_ready_without_a_key() {
        assertFalse(a.update(null, visible = false))
        assertNull(a.handle)
    }

    @Test fun due_while_hidden_and_kept_for_the_same_key() {
        assertTrue(a.update("ch1", visible = false))
        a.prepared("ch1", "h1")
        // a page turn, the same chapter: nothing to do, the prepared panel stays
        assertFalse(a.update("ch1", visible = false))
        assertEquals("h1", a.handle)
        assertEquals(emptyList(), disposed)
    }

    @Test fun never_while_it_shows_or_is_held_by_a_finger() {
        assertFalse(a.update("ch1", visible = true))
        assertNull(a.handle)
    }

    @Test fun the_layout_takes_the_prepared_one_over_when_it_shows_so_it_is_not_disposed() {
        a.update("ch1", false); a.prepared("ch1", "h1")
        assertFalse(a.update("ch1", visible = true))
        assertNull(a.handle)
        assertEquals(emptyList(), disposed)
        // gone again: got ready afresh (the layout disposed the shown one)
        assertTrue(a.update("ch1", visible = false))
    }

    @Test fun another_chapter_or_tab_disposes_the_stale_one_and_is_due_again() {
        a.update("ch1", false); a.prepared("ch1", "h1")
        assertTrue(a.update("ch2", visible = false))
        assertEquals(listOf("h1"), disposed)
        assertNull(a.handle)
    }

    @Test fun the_book_closing_disposes_it() {
        a.update("ch1", false); a.prepared("ch1", "h1")
        assertFalse(a.update(null, visible = false))
        assertEquals(listOf("h1"), disposed)
    }
}
