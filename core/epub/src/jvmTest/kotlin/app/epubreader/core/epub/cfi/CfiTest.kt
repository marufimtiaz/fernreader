package app.epubreader.core.epub.cfi

import app.epubreader.core.epub.TextPos
import app.epubreader.core.epub.chunk.ChunkMap
import app.epubreader.core.epub.chunk.ChunkMap.Part
import app.epubreader.core.epub.chunk.ChunkMap.Slot
import app.epubreader.core.epub.chunk.ChunkMap.SplitDoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CfiTest {
    // ---- parse / format ------------------------------------------------------------------------------------------

    @Test fun parse_and_print_point() {
        val s = "epubcfi(/6/14[chap05ref]!/4[body01]/10/2/1:3[2^[1^]])"
        val c = Cfi.parse(s)!!
        assertEquals(s, c.toString())
        assertFalse(c.isRange)
        assertEquals(listOf(6, 14, 4, 10, 2, 1), c.parent.steps.map { it.index })
        assertEquals("chap05ref", c.parent.steps[1].assertion)
        assertTrue(c.parent.steps[1].indirect)
        assertEquals(":3[2^[1^]]", c.parent.terminal)
    }

    @Test fun parse_and_print_range() {
        val s = "epubcfi(/6/4!/4/10,/2/1:1,/3:4)"
        val c = Cfi.parse(s)!!
        assertEquals(s, c.toString())
        assertTrue(c.isRange)
        assertEquals("epubcfi(/6/4!/4/10/2/1:1)", Cfi(c.startPoint()).toString())
        assertEquals("epubcfi(/6/4!/4/10/3:4)", Cfi(c.endPoint()).toString())
    }

    @Test fun terminals_and_escaped_commas_are_kept_verbatim() {
        for (s in listOf("epubcfi(/6/4!/4/2/1:0[;s=a])", "epubcfi(/6/4!/4/2[a^,b;s=b])", "epubcfi(/6/4!/4/3~1.5@10:20)",
            "epubcfi(/6/4!/4,/2/1:0[x^,y],/4/1:3)")) assertEquals(s, Cfi.parse(s).toString())
    }

    @Test fun garbage_is_null() {
        for (s in listOf("not a cfi", "epubcfi()", "epubcfi(/x)", "epubcfi(/6/4!/4", "epubcfi(/6,/2)", "epubcfi(/6/4[abc)"))
            assertNull(Cfi.parse(s), s)
    }

    // ---- hand-written map --------------------------------------------------------------------------------------
    // <spine>: a.xhtml (itemref id "ia"), an itemref with an unknown idref, big.xhtml (id "ib"), c.xhtml.
    // Original itemref steps: a /6/2, ghost /6/4, big /6/6, c /6/8. Sections: a 0, big 1, c 2.
    // big is split at body level into part0 = children 0..9, part1 = 10..24, part2 = 25..29.
    // Virtual itemref steps: a /6/2, ghost /6/4, big#0 /6/6, big#1 /6/8, big#2 /6/10, c /6/12. Sections: a 0, big#0..2 1..3, c 4.
    private fun map(wrapped: Boolean) = ChunkMap(
        policyKey = "test",
        spine = listOf(Slot("a.xhtml", "ia"), Slot(null), Slot("big.xhtml", "ib"), Slot("c.xhtml")),
        docs = mapOf("big.xhtml" to SplitDoc("big.xhtml", bodyStep = 4, wrapped = wrapped, parts = listOf(
            Part("big.part0.xhtml", 0, 10, 0, 1000),
            Part("big.part1.xhtml", 10, 15, 1000, 1500),
            Part("big.part2.xhtml", 25, 5, 2500, 500)), idToPart = mapOf("x" to 1, "y" to 0))),
    )
    private val flat = map(wrapped = false)
    private val wrapped = map(wrapped = true)

    @Test fun virtual_to_original_shifts_spine_and_child_step() {
        // 3rd element of part1 (step 6) = original element 12 (step 26); the split itemref's id comes back
        assertEquals("epubcfi(/6/6[ib]!/4/26/1:5)", flat.cfiToOriginal("epubcfi(/6/8!/4/6/1:5)"))
        // a document after the split one moves back by the two added itemrefs; unsplit assertions are kept
        assertEquals("epubcfi(/6/8!/4/2/1:0)", flat.cfiToOriginal("epubcfi(/6/12!/4/2/1:0)"))
        assertEquals("epubcfi(/6/2[ia]!/4/2/1:0)", flat.cfiToOriginal("epubcfi(/6/2[ia]!/4/2/1:0)"))
    }

    @Test fun original_to_virtual_picks_part() {
        assertEquals("epubcfi(/6/8!/4/6/1:5)", flat.cfiToVirtual("epubcfi(/6/6[ib]!/4/26/1:5)"))
        assertEquals("epubcfi(/6/10!/4/2)", flat.cfiToVirtual("epubcfi(/6/6!/4/52)"))       // element 25 -> part2 first
        assertEquals("epubcfi(/6/10!/4/11)", flat.cfiToVirtual("epubcfi(/6/6!/4/61)"))      // gap after the last element
        assertEquals("epubcfi(/6/8!/4/1:3)", flat.cfiToVirtual("epubcfi(/6/6!/4/21:3)"))    // gap before element 10
        assertEquals("epubcfi(/6/12!/4/2)", flat.cfiToVirtual("epubcfi(/6/8!/4/2)"))
        assertEquals("epubcfi(/6/4!/4/2)", flat.cfiToVirtual("epubcfi(/6/4!/4/2)"))         // unknown idref: same slot
    }

    @Test fun round_trip_every_element_and_gap() {
        for (m in listOf(flat, wrapped)) {
            val container = if (m === flat) "/4" else "/4/2"
            for (step in 0..62) for (tail in listOf("", "/1:0", "/3/1:7", ":4")) {
                if (tail == ":4" && step % 2 == 0) continue
                val o = "epubcfi(/6/6[ib]!$container/$step$tail)"
                assertEquals(o, m.cfiToOriginal(m.cfiToVirtual(o)), o)
            }
        }
    }

    @Test fun every_virtual_element_maps_to_its_original_index() {
        val parts = flat.docs.getValue("big.xhtml").parts
        for ((p, part) in parts.withIndex()) for (k in 0 until part.childCount) {
            val v = "epubcfi(/6/${2 * (3 + p)}!/4/${2 * (k + 1)})"
            assertEquals("epubcfi(/6/6[ib]!/4/${2 * (part.childOffset + k + 1)})", flat.cfiToOriginal(v), v)
            assertEquals(v, flat.cfiToVirtual(flat.cfiToOriginal(v)))
        }
    }

    @Test fun head_positions_go_to_the_first_part() {
        assertEquals("epubcfi(/6/6!/2/4)", flat.cfiToVirtual("epubcfi(/6/6[ib]!/2/4)"))
        assertEquals("epubcfi(/6/6[ib]!/2/4)", flat.cfiToOriginal("epubcfi(/6/6!/2/4)"))
    }

    @Test fun container_only_cfi_points_at_part_start() {
        assertEquals("epubcfi(/6/6[ib]!/4/22)", flat.cfiToOriginal("epubcfi(/6/8!/4)"))
        assertEquals("epubcfi(/6/6[ib]!/4)", flat.cfiToOriginal("epubcfi(/6/6!/4)"))
        assertEquals("epubcfi(/6/6!/4)", flat.cfiToVirtual("epubcfi(/6/6[ib]!/4)"))
        assertEquals("epubcfi(/6/6[ib]!/4/2/22)", wrapped.cfiToOriginal("epubcfi(/6/8!/4)"))
        assertEquals("epubcfi(/6/6[ib]!/4/2/22)", wrapped.cfiToOriginal("epubcfi(/6/8!/4/2)"))
        assertEquals("epubcfi(/6/6[ib]!/4/2)", wrapped.cfiToOriginal("epubcfi(/6/6!/4/2)"))
    }

    @Test fun wrapper_shifts_the_step_below_it_and_keeps_text_around_it() {
        assertEquals("epubcfi(/6/8!/4/2/6/1:5)", wrapped.cfiToVirtual("epubcfi(/6/6[ib]!/4/2/26/1:5)"))
        assertEquals("epubcfi(/6/6!/4/1:3)", wrapped.cfiToVirtual("epubcfi(/6/6[ib]!/4/1:3)"))   // before the wrapper: part0
        assertEquals("epubcfi(/6/10!/4/3:0)", wrapped.cfiToVirtual("epubcfi(/6/6[ib]!/4/3:0)"))  // after it: last part
        assertEquals("epubcfi(/6/6[ib]!/4/3:0)", wrapped.cfiToOriginal("epubcfi(/6/10!/4/3:0)"))
    }

    @Test fun range_within_part_round_trips_and_keeps_its_shape() {
        val o = "epubcfi(/6/6[ib]!/4/26,/1:2,/1:9)"
        assertEquals("epubcfi(/6/8!/4/6,/1:2,/1:9)", flat.cfiToVirtual(o))
        assertEquals(o, flat.cfiToOriginal(flat.cfiToVirtual(o)))
        val wide = "epubcfi(/6/6[ib]!/4,/26/1:2,/28/1:3)"   // container step inside start and end
        assertEquals("epubcfi(/6/8!/4,/6/1:2,/8/1:3)", flat.cfiToVirtual(wide))
        assertEquals(wide, flat.cfiToOriginal(flat.cfiToVirtual(wide)))
    }

    @Test fun range_across_parts_is_clamped_to_the_end_of_the_first_part() {
        // element 8 is in part0, element 10 in part1: the reader can only show one part, so the range ends after part0
        assertEquals("epubcfi(/6/6!/4,/18/1:0,/22)", flat.cfiToVirtual("epubcfi(/6/6[ib]!/4,/18/1:0,/22/1:4)"))
    }

    @Test fun section_only_cfi_keeps_its_part() {
        // the reader's CFI for a whole section (no range yet) is the bare itemref step
        assertEquals("epubcfi(/6/6[ib]!/4/22)", flat.cfiToOriginal("epubcfi(/6/8)"))
        assertEquals("epubcfi(/6/6[ib]!/4/2/22)", wrapped.cfiToOriginal("epubcfi(/6/8)"))
        assertEquals("epubcfi(/6/6[ib])", flat.cfiToOriginal("epubcfi(/6/6)"))
        assertEquals("epubcfi(/6/6)", flat.cfiToVirtual("epubcfi(/6/6[ib])"))
    }

    @Test fun range_from_the_container_across_parts_is_clamped() {
        assertEquals("epubcfi(/6/6!/4,,/22)", flat.cfiToVirtual("epubcfi(/6/6[ib]!/4,,/58/1:3)"))
        // start and end in different documents cannot be shown as one range: the start point is kept
        assertEquals("epubcfi(/6/2[ia]!/4/2/1:0)", flat.cfiToVirtual("epubcfi(/6,/2[ia]!/4/2/1:0,/8!/4/2/1:0)"))
    }

    @Test fun range_with_terminal_only_start_and_end_keeps_its_shape() {
        val o = "epubcfi(/6/6[ib]!/4/26/1,:2,:9)"
        assertEquals("epubcfi(/6/8!/4/6/1,:2,:9)", flat.cfiToVirtual(o))
        assertEquals(o, flat.cfiToOriginal(flat.cfiToVirtual(o)))
    }

    @Test fun unparseable_and_non_spine_cfis_pass_through() {
        assertEquals("garbage", flat.cfiToVirtual("garbage"))
        assertEquals("epubcfi(/6)", flat.cfiToOriginal("epubcfi(/6)"))
        assertEquals("epubcfi(/6/99!/4/2)", flat.cfiToOriginal("epubcfi(/6/99!/4/2)"))
    }

    @Test fun hrefs() {
        assertEquals("big.part1.xhtml#x", flat.hrefToVirtual("big.xhtml#x"))
        assertEquals("big.part0.xhtml#y", flat.hrefToVirtual("big.xhtml#y"))
        assertEquals("big.part0.xhtml", flat.hrefToVirtual("big.xhtml"))
        assertEquals("big.part0.xhtml#nope", flat.hrefToVirtual("big.xhtml#nope"))
        assertEquals("big.xhtml#x", flat.hrefToOriginal("big.part1.xhtml#x"))
        assertEquals("big.xhtml", flat.hrefToOriginal("big.part2.xhtml"))
        assertEquals("c.xhtml#y", flat.hrefToVirtual("c.xhtml#y"))
        assertEquals("c.xhtml#y", flat.hrefToOriginal("c.xhtml#y"))
    }

    @Test fun text_positions() {
        assertEquals(TextPos(1, 0), flat.textToVirtual(TextPos(1, 0)))
        assertEquals(TextPos(1, 999), flat.textToVirtual(TextPos(1, 999)))
        assertEquals(TextPos(2, 0), flat.textToVirtual(TextPos(1, 1000)))
        assertEquals(TextPos(2, 200), flat.textToVirtual(TextPos(1, 1200)))
        assertEquals(TextPos(3, 500), flat.textToVirtual(TextPos(1, 3000)))  // end of text -> end of the last part
        assertEquals(TextPos(4, 7), flat.textToVirtual(TextPos(2, 7)))
        assertEquals(TextPos(0, 7), flat.textToVirtual(TextPos(0, 7)))
        for (off in 0..3000) assertEquals(TextPos(1, off), flat.textToOriginal(flat.textToVirtual(TextPos(1, off))))
        assertEquals(TextPos(2, 7), flat.textToOriginal(TextPos(4, 7)))
        assertEquals(TextPos(1, 1200), flat.textToOriginal(TextPos(2, 200)))
    }

    @Test fun section_counts() {
        assertEquals(listOf("a.xhtml", "big.xhtml", "c.xhtml"), flat.spinePaths)
        assertEquals(listOf("a.xhtml", "big.part0.xhtml", "big.part1.xhtml", "big.part2.xhtml", "c.xhtml"), flat.virtualSpinePaths)
        assertEquals(setOf("big.part0.xhtml", "big.part1.xhtml", "big.part2.xhtml"), flat.partPaths)
    }

    @Test fun identity_map_is_pass_through() {
        val id = ChunkMap.identity(listOf(Slot("a"), Slot("b")), "p")
        assertTrue(id.isIdentity)
        assertEquals("epubcfi(/6/4!/4/2/1:0)", id.cfiToVirtual("epubcfi(/6/4!/4/2/1:0)"))
        assertEquals(TextPos(1, 5), id.textToVirtual(TextPos(1, 5)))
        assertEquals("a#x", id.hrefToVirtual("a#x"))
    }

    @Test fun map_survives_json() = assertEquals(flat, ChunkMap.fromJson(flat.toJson()))
}
