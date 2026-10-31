package app.epubreader.reader.toc

import app.epubreader.reader.bridge.TocItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TocTreeStateTest {
    /** Shape of stress-2000-chapters: 20 parts x 100 chapters. */
    private val items = (1..20).flatMap { p ->
        listOf(TocItem("Part $p", "p$p.xhtml", 0)) + (1..100).map { c -> TocItem("Chapter $p.$c", "c$p-$c.xhtml", 1) }
    }

    @Test fun collapsed_by_default_shows_top_level_only() = assertEquals(20, TocTreeState(items).visibleRows().size)

    @Test fun expanding_one_part_shows_its_chapters() {
        val t = TocTreeState(items); t.toggle(0)
        assertEquals(120, t.visibleRows().size)
        t.toggle(0)
        assertEquals(20, t.visibleRows().size)
    }

    @Test fun reveal_current_entry_expands_its_ancestors() {
        val t = TocTreeState(items)
        val i = t.indexOfHref("c18-77.xhtml")!!
        t.reveal(i)
        assertTrue(i in t.visibleRows())
        assertEquals(120, t.visibleRows().size)
    }

    @Test fun irregular_depths_do_not_break_the_tree() {
        val t = TocTreeState(listOf(TocItem("a", "a", 0), TocItem("b", "b", 2), TocItem("c", "c", 1), TocItem("d", "d", 0)))
        assertEquals(listOf(0, 3), t.visibleRows())
        t.toggle(0)
        assertEquals(listOf(0, 1, 2, 3), t.visibleRows())
    }
}
