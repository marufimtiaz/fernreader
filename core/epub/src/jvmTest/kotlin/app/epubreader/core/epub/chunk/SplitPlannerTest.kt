package app.epubreader.core.epub.chunk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SplitPlannerTest {
    private fun doc(body: String, head: String = "<head><title>t</title></head>") =
        "<?xml version=\"1.0\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\">$head\n<body class=\"b\">$body</body>\n</html>"
    private fun paras(n: Int) = (0 until n).joinToString("\n") { "<p id=\"p$it\">Paragraph $it ${"x".repeat(80)}</p>" }

    @Test fun cuts_after_whole_body_children() {
        val s = doc("\n" + paras(100) + "\n")
        val plan = assertNotNull(SplitPlanner.plan(s, 1000))
        assertEquals(4, plan.bodyStep)
        assertTrue(plan.parts.size in 8..12, "${plan.parts.size} parts")
        assertEquals(100, plan.parts.sumOf { it.childCount })
        plan.parts.zipWithNext().forEach { (a, b) ->
            assertEquals(a.childOffset + a.childCount, b.childOffset)
            assertEquals(a.sliceEnd, b.sliceStart)
            assertEquals("</p>", s.substring(a.sliceEnd - 4, a.sliceEnd))
        }
        assertEquals(s.indexOf("<body class=\"b\">") + 16, plan.parts.first().sliceStart)
        assertEquals(s.indexOf("</body>"), plan.parts.last().sliceEnd)
        assertNull(plan.wrapper)
    }

    @Test fun body_step_counts_elements_before_body() {
        assertEquals(2, SplitPlanner.plan(doc(paras(100), head = ""), 1000)!!.bodyStep)
    }

    @Test fun single_wrapper_is_split_inside() {
        val s = doc("\n<div class=\"wrap\">\n${paras(100)}\n</div>\n")
        val plan = assertNotNull(SplitPlanner.plan(s, 1000))
        val w = assertNotNull(plan.wrapper)
        assertEquals("<div class=\"wrap\">", s.substring(w.openStart, w.openEnd))
        assertEquals("</div>", s.substring(w.closeStart, w.closeEnd))
        assertEquals("div", w.name)
        assertEquals(w.openEnd, plan.parts.first().sliceStart)
        assertEquals(w.closeStart, plan.parts.last().sliceEnd)
        assertEquals(100, plan.parts.sumOf { it.childCount })
    }

    @Test fun void_and_self_closing_elements_are_children() {
        val s = doc((0 until 100).joinToString("") { "<p>${"y".repeat(90)}</p><br><hr/><img src=\"a.png\"></img>" })
        assertEquals(400, SplitPlanner.plan(s, 1000)!!.parts.sumOf { it.childCount })
    }

    @Test fun script_and_comments_do_not_confuse_the_structure() {
        val s = doc(paras(50) + "<script>if (a < b) { x = '</p>' }</script><!-- <p> -->" + paras(50))
        assertEquals(101, SplitPlanner.plan(s, 1000)!!.parts.sumOf { it.childCount })
    }

    @Test fun unbalanced_markup_is_not_split() {
        for (bad in listOf(paras(100) + "<div><p>unclosed", paras(100) + "</div>", paras(50) + "<p><b>x</p></b>" + paras(50),
            paras(100) + "<p>a < b</p>", paras(100) + "<p class=\"x>y</p>"))
            assertNull(SplitPlanner.plan(doc(bad), 1000), bad.takeLast(30))
        assertNull(SplitPlanner.plan("<html><body>" + paras(100), 1000))   // no </body>
        assertNull(SplitPlanner.plan(paras(100), 1000))                    // no body at all
    }

    @Test fun nothing_to_cut() {
        assertNull(SplitPlanner.plan(doc("<div>${"z".repeat(5000)}</div>"), 1000))  // one child
        assertNull(SplitPlanner.plan(doc(paras(5)), 100_000))                      // under one part
    }

    @Test fun tiny_last_part_is_merged() {
        val plan = SplitPlanner.plan(doc(paras(21)), 1000)!!
        assertTrue(plan.parts.last().childCount > 1, plan.parts.map { it.childCount }.toString())
    }
}
