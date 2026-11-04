package app.epubreader.ui

import app.epubreader.reader.PageIndicator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** C-091: the reader shows "212/718"; screen readers keep the full wording. */
class PageLabelTest {
    private fun both(p: PageIndicator) = Copy.pageLabelShort(p) to Copy.pageLabel(p)

    @Test fun exact_total() = assertEquals("212/718" to "Page 212 of 718", both(PageIndicator(page = 212, total = 718, exact = true, chapterScope = false)))
    @Test fun estimate_keeps_its_mark() = assertEquals("212/≈718" to "Page 212 of ≈ 718", both(PageIndicator(page = 212, total = 718, exact = false, chapterScope = false)))
    @Test fun print_label() = assertEquals("xii/400" to "Page xii of 400", both(PageIndicator(page = 12, total = 400, exact = true, chapterScope = false, label = "xii")))
    @Test fun print_label_without_a_total() = assertEquals("xii" to "Page xii", both(PageIndicator(page = 12, total = null, exact = false, chapterScope = false, label = "xii")))
    @Test fun no_total() = assertEquals("12" to "Page 12", both(PageIndicator(page = 12, total = null, exact = false, chapterScope = false)))
    @Test fun chapter_scope_says_so() = assertEquals("3/9 in chapter" to "Page 3 of 9 in chapter", both(PageIndicator(page = 3, total = 9, exact = true, chapterScope = true)))
    @Test fun thousands_keep_their_grouping() = assertEquals("1,212/12,718", Copy.pageLabelShort(PageIndicator(page = 1212, total = 12718, exact = true, chapterScope = false)))
    @Test fun no_page_no_label() = assertNull(Copy.pageLabelShort(PageIndicator(page = null, total = null, exact = false, chapterScope = false)))
}
