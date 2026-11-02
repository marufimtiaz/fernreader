package app.epubreader.reader

import app.epubreader.core.epub.PositionMap
import app.epubreader.core.epub.TextPos
import app.epubreader.core.epub.pages.BookPages
import app.epubreader.core.epub.pages.PageSource
import app.epubreader.reader.bridge.FromWeb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookPageNumbersTest {
    /** Print pages i..iv: i at 0:0, ii at 0:1000, iii at 1:0, iv at 1:2000; sections 3000 and 4000 chars long. */
    private val print = BookPages.decode(PageSource.NAV.name, "i\nii\niii\niv", "0.0,0.1000,1.0,1.2000", "3000,4000")!!
    private val virtual = BookPages.decode(PageSource.VIRTUAL.name, null, "0.0,0.1000,1.0,1.2000", "3000,4000")!!
    private fun at(section: Int, start: Int, end: Int = start + 800) =
        FromWeb.Relocate(sessionId = "s", cfi = "c$section:$start", fraction = 0.0, sectionIndex = section, textOffset = start, textEnd = end)

    @Test fun the_page_of_the_first_visible_character_with_its_print_label() {
        val n = BookPageNumbers()
        assertEquals(BookPageNumbers.NONE, n.state.value)
        n.onRelocate(at(0, 1200)); assertNull(n.state.value.page) // no pages yet: nothing shown
        n.setPages(print, PositionMap.Identity)
        assertEquals(PageIndicator(2, 4, exact = true, chapterScope = false, label = "ii"), n.state.value)
        n.onRelocate(at(0, 999)); assertEquals("i", n.state.value.label) // a page break further down the screen does not count
        n.onRelocate(at(1, 2000)); assertEquals("iv", n.state.value.label)
        assertEquals("Page iv of 4", app.epubreader.ui.Copy.pageLabel(n.state.value))
        n.setPages(virtual, PositionMap.Identity)
        assertEquals(PageIndicator(4, 4, exact = true, chapterScope = false, label = null), n.state.value)
        assertEquals("Page 4 of 4", app.epubreader.ui.Copy.pageLabel(n.state.value))
    }

    @Test fun a_layout_change_keeps_the_number_while_its_text_is_on_screen() {
        val n = BookPageNumbers().apply { setPages(print, PositionMap.Identity); onRelocate(at(0, 1100)) }
        assertEquals("ii", n.state.value.label)
        n.pinHere() // larger text: the screen now begins before the page break, the old first character still on it
        n.onRelocate(at(0, 700, end = 1300)); assertEquals("ii", n.state.value.label)
        n.onRelocate(at(0, 400, end = 1050)); assertEquals("i", n.state.value.label) // moved on: the pin is gone
        n.onRelocate(at(0, 700, end = 1300)); assertEquals("i", n.state.value.label)
    }

    @Test fun go_to_a_label_pins_the_page_whose_start_the_screen_shows() {
        val n = BookPageNumbers().apply { setPages(print, PositionMap.Identity); onRelocate(at(0, 0)) }
        assertNull(n.goTo("xii"))
        assertEquals(1 to TextPos(0, 1000), n.goTo("II"))
        n.onRelocate(at(0, 600, end = 1400)) // landed: the screen starts on page i and holds ii's start
        assertEquals("ii", n.state.value.label)
        n.onRelocate(at(0, 1400, end = 2200)); assertEquals("ii", n.state.value.label) // next screen: ii by the rule
    }

    @Test fun go_to_a_number_for_virtual_pages_and_labels_for_lists() {
        val n = BookPageNumbers().apply { setPages(virtual, PositionMap.Identity) }
        assertEquals(2 to TextPos(1, 0), n.goTo("3"))
        assertNull(n.goTo("5"))
        assertEquals("2", n.labelAt(TextPos(0, 2999)))
        n.setPages(print, PositionMap.Identity)
        assertEquals("iii", n.labelAt(TextPos(1, 1999)))
    }

    /** M8: a chunked document's parts are virtual sections 1 and 2 of original spine item 1 (part 2 at text 2500). */
    private val chunked = object : PositionMap {
        override fun cfiToOriginal(virtualCfi: String) = virtualCfi
        override fun cfiToVirtual(originalCfi: String) = originalCfi
        override fun textToVirtual(p: TextPos) = when {
            p.spineIndex == 0 -> p
            p.spineIndex == 1 && p.offset < 2500 -> TextPos(1, p.offset)
            p.spineIndex == 1 -> TextPos(2, p.offset - 2500)
            else -> TextPos(p.spineIndex + 1, p.offset)
        }
        override fun textToOriginal(p: TextPos) = when (p.spineIndex) {
            0 -> p; 1 -> TextPos(1, p.offset); 2 -> TextPos(1, p.offset + 2500); else -> TextPos(p.spineIndex - 1, p.offset)
        }
    }

    @Test fun chunked_parts_get_the_unchunked_numbers_in_the_indicator_preview_and_go_to() {
        val n = BookPageNumbers().apply { setPages(print, chunked) }
        n.onRelocate(at(2, 0)); assertEquals("iv", n.state.value.label) // part 2 starts at 2500 of the original
        n.onRelocate(at(1, 1999)); assertEquals("iii", n.state.value.label)
        assertEquals(3 to TextPos(1, 2000), n.goTo("iv").let { it!!.first to chunked.textToOriginal(it.second) })
        // Preview: part 1 spans 0..2500 of the original, part 2 2500..4000.
        assertEquals("iii", n.previewAt(1, 0.5).label)
        assertEquals("iv", n.previewAt(1, 0.9).label)
        assertEquals("iv", n.previewAt(2, 0.0).label)
        assertEquals("ii", n.previewAt(0, 0.5).label)
    }
}
