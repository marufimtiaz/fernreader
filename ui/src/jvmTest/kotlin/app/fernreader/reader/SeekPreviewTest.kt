package app.fernreader.reader

import app.fernreader.reader.bridge.TocItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeekPreviewTest {
    // 5 sections: 0 cover (not linear: size 0), 1..3 text, 4 notes (not linear)
    private val sizes = listOf(0L, 1_000L, 2_000L, 1_000L, 0L)

    private fun near(expected: Pair<Int, Double>, actual: Pair<Int, Double>?) {
        assertEquals(expected.first, actual?.first)
        assertEquals(expected.second, actual!!.second, 1e-9)
    }

    @Test fun section_at_follows_foliates_section_fractions() {
        near(1 to 0.5, sectionAt(sizes, 0.125)) // 500 of 4000 bytes
        near(2 to 0.25, sectionAt(sizes, 0.375)) // 1500: 500 into section 2
        near(3 to 0.0, sectionAt(sizes, 0.75))
        near(3 to 0.5, sectionAt(sizes, 0.875))
    }

    @Test fun section_at_the_ends() {
        near(0 to 0.0, sectionAt(sizes, 0.0))
        near(0 to 0.0, sectionAt(sizes, -1.0))
        near(4 to 1.0, sectionAt(sizes, 1.0))
        // just after the start: the empty cover is skipped like foliate does
        assertEquals(1, sectionAt(sizes, 0.001)?.first)
    }

    @Test fun no_sizes_no_section() {
        assertNull(sectionAt(emptyList(), 0.5))
        assertNull(sectionAt(listOf(0L, 0L), 0.5))
    }

    private val toc = listOf(
        TocItem("Part One", "p1.xhtml", 0, index = 1),
        TocItem("Chapter 1", "p1.xhtml#c1", 1, index = 1),
        TocItem("Chapter 2", "c2.xhtml", 1, index = 2),
        TocItem("Gone", "gone.xhtml", 1, missing = true, index = -1),
        TocItem("Notes", "notes.xhtml", 0, index = 4),
    )

    @Test fun entry_of_a_section_with_one_entry() =
        assertEquals("Chapter 2", tocEntryAt(toc, 2, 0.5)?.label)

    @Test fun a_section_without_an_entry_belongs_to_the_last_entry_before_it() =
        assertEquals("Chapter 2", tocEntryAt(toc, 3, 0.1)?.label)

    @Test fun before_the_first_entry_there_is_no_chapter() =
        assertNull(tocEntryAt(toc, 0, 0.0))

    @Test fun several_entries_in_one_section_split_it_evenly() {
        assertEquals("Part One", tocEntryAt(toc, 1, 0.2)?.label)
        assertEquals("Chapter 1", tocEntryAt(toc, 1, 0.7)?.label)
        assertEquals("Chapter 1", tocEntryAt(toc, 1, 1.0)?.label)
    }

    @Test fun in_the_current_section_the_current_entry_is_kept() =
        assertEquals("Chapter 1", tocEntryAt(toc, 1, 0.1, current = "p1.xhtml#c1")?.label)

    @Test fun chapter_label_is_the_trimmed_entry_label_or_null() {
        val t = listOf(TocItem("  One ", "a.xhtml", 0, index = 0), TocItem(" ", "b.xhtml", 0, index = 1))
        assertEquals("One", chapterLabel(t, "a.xhtml"))
        assertNull(chapterLabel(t, "b.xhtml"))
        assertNull(chapterLabel(t, null))
    }
}
