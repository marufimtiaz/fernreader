package app.epubreader.reader

import app.epubreader.reader.bridge.TocItem
import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterTitleTest {
    private val toc = listOf(
        TocItem(label = "One", href = "a.xhtml", depth = 0, index = 0),
        TocItem(label = "  ", href = "b.xhtml", depth = 0, index = 1),
        TocItem(label = "Two", href = "c.xhtml#x", depth = 1, index = 2),
    )

    @Test fun label_of_the_entry_for_the_current_href() = assertEquals("Two", chapterTitle(toc, "c.xhtml#x", "Book"))
    @Test fun falls_back_to_the_book_title_without_a_match() {
        assertEquals("Book", chapterTitle(toc, "zzz.xhtml", "Book"))
        assertEquals("Book", chapterTitle(toc, null, "Book"))
        assertEquals("Book", chapterTitle(emptyList(), "a.xhtml", "Book"))
    }
    @Test fun blank_labels_fall_back_too() = assertEquals("Book", chapterTitle(toc, "b.xhtml", "Book"))
}
