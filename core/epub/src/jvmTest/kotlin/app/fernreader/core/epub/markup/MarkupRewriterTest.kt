package app.fernreader.core.epub.markup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MarkupRewriterTest {
    @Test fun rewrites_only_selected_attribute_values() {
        val s = """<p>href="#a" in text</p><a href="#a">x</a><img src="i.png"/>"""
        val r = MarkupRewriter.rewriteAttrValues(s) { tag, a -> if (tag == "a" && a.localName == "href") "p1.xhtml#a" else null }
        assertEquals("""<p>href="#a" in text</p><a href="p1.xhtml#a">x</a><img src="i.png"/>""", r)
    }
    @Test fun escapes_new_values() = assertEquals("""<a href="a&amp;b">x</a>""",
        MarkupRewriter.rewriteAttrValues("""<a href="z">x</a>""") { _, _ -> "a&b" })
    @Test fun unchanged_returns_same_instance() { val s = "<a href='x'/>"; assertSame(s, MarkupRewriter.rewriteAttrValues(s) { _, _ -> null }) }
}
