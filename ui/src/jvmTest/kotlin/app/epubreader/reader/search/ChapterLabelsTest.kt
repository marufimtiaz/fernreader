package app.epubreader.reader.search

import app.epubreader.reader.bridge.TocItem
import app.epubreader.ui.Copy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChapterLabelsTest {
    private fun t(label: String, index: Int, depth: Int = 0) = TocItem(label, "x$index.xhtml", depth, index = index)

    @Test fun a_section_takes_the_label_of_the_last_entry_at_or_before_it() {
        val l = ChapterLabels(listOf(t("One", 1), t("Two", 4), t("Three", 7)))
        assertEquals("One", l.label(1))
        assertEquals("One", l.label(3))
        assertEquals("Two", l.label(4))
        assertEquals("Two", l.label(6))
        assertEquals("Three", l.label(99))
    }

    @Test fun sections_before_the_first_entry_have_no_label() {
        val l = ChapterLabels(listOf(t("One", 2)))
        assertNull(l.label(0))
        assertNull(l.label(1))
    }

    @Test fun ties_go_to_the_later_entry_in_toc_order() {
        val l = ChapterLabels(listOf(t("Part I", 0), t("Chapter 1", 0, depth = 1), t("Chapter 2", 3, depth = 1)))
        assertEquals("Chapter 1", l.label(0))
        assertEquals("Chapter 1", l.label(2))
    }

    @Test fun entries_may_be_out_of_spine_order() {
        val l = ChapterLabels(listOf(t("Late", 5), t("Early", 1)))
        assertEquals("Early", l.label(2))
        assertEquals("Late", l.label(5))
    }

    @Test fun unresolved_entries_are_ignored_and_blank_labels_read_untitled() {
        val l = ChapterLabels(listOf(t("Gone", -1), t("  ", 0)))
        assertEquals(Copy.untitledEntry, l.label(3))
        assertNull(ChapterLabels(listOf(t("Gone", -1))).label(0))
        assertNull(ChapterLabels(emptyList()).label(0))
    }
}
