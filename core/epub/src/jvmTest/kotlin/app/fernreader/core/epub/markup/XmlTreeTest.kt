package app.fernreader.core.epub.markup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XmlTreeTest {
    @Test fun well_formed_package() {
        val d = XmlTree.parse("""<?xml version="1.0"?><package xmlns="x" version="3.0"><metadata><dc:title>T &amp; U</dc:title></metadata><spine><itemref idref="a"/></spine></package>""")
        assertTrue(d.wellFormed)
        assertEquals("T & U", d.root!!.first("title")!!.text())
        assertEquals("a", d.root!!.first("itemref")!!.attr("idref"))
    }

    @Test fun broken_opf_from_corpus_is_not_well_formed() {
        val d = XmlTree.parse("""<?xml version="1.0"?><package version="3.0"><metadata><dc:title>Broken OPF</metadata><manifest><item id="c1" href="good.xhtml"</manifest>""")
        assertFalse(d.wellFormed)
        assertEquals("package", d.root!!.localName) // lenient tree still available
    }

    @Test fun html_mode_void_elements_do_not_nest() {
        val d = XmlTree.parse("<nav><ol><li><a href='a'>A</a><br><img src=x></li><li><a href='b'>B</a></li></ol></nav>", html = true)
        assertEquals(2, d.root!!.first("ol")!!.elements().size)
    }

    @Test fun mismatched_end_tags_recover() {
        val d = XmlTree.parse("<p><b>bold <i>mis</b> nest</i></p>", html = true)
        assertFalse(d.errors.isEmpty())
        assertEquals("bold mis nest", d.root!!.text())
    }

    // Final review C1: hostile documents give a bounded tree and errors, never a stack overflow or an OOM.
    @Test fun very_deep_nesting_is_bounded() {
        val n = 100_000
        val d = XmlTree.parse("<package>" + "<a>".repeat(n) + "x" + "</a>".repeat(n) + "</package>")
        assertTrue(d.errors.any { "deeper" in it }, "${d.errors}")
        assertEquals("x", d.root!!.text())
        assertTrue(d.root!!.descendants().count() <= XmlTree.MAX_ELEMENTS)
        assertEquals(null, d.root!!.first("manifest"))
    }

    @Test fun very_many_elements_are_bounded() {
        val d = XmlTree.parse("<package>" + "<a/>".repeat(2_000_000) + "</package>")
        assertTrue(d.errors.any { "more than" in it })
        assertEquals(XmlTree.MAX_ELEMENTS - 1, d.root!!.descendants().count())
    }

    @Test fun error_list_is_bounded() {
        val d = XmlTree.parse("<package>" + "</b>".repeat(100_000) + "</package>")
        assertEquals(XmlTree.MAX_ERRORS, d.errors.size)
    }
}
