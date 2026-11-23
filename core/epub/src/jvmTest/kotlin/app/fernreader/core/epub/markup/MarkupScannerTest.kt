package app.fernreader.core.epub.markup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkupScannerTest {
    private fun events(s: String): List<String> {
        val out = ArrayList<String>()
        MarkupScanner.scan(s, object : MarkupVisitor {
            override fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) {
                out += "<$name${attrs.joinToString("") { " ${it.name}=${it.value}" }}${if (selfClosing) "/" else ""}>"
            }
            override fun endTag(name: String, start: Int, end: Int) { out += "</$name>" }
            override fun text(start: Int, end: Int) { out += "T:" + s.substring(start, end) }
            override fun cdata(start: Int, end: Int) { out += "C:" + s.substring(start, end) }
            override fun error(message: String, at: Int) { out += "E:$message" }
        })
        return out
    }

    @Test fun tags_text_attrs() = assertEquals(
        listOf("<p class=a b=x&y>", "T:hi", "<br/>", "</p>"),
        events("""<p class="a" b='x&amp;y'>hi<br/></p>"""))

    @Test fun comments_pi_doctype_skipped_cdata_reported() = assertEquals(
        listOf("<a>", "C:x<y", "</a>"),
        events("""<?xml version="1.0"?><!DOCTYPE html><!-- c --><a><![CDATA[x<y]]></a>"""))

    @Test fun script_is_raw_text() = assertEquals(
        listOf("<script>", "T:if (a<b) x()", "</script>"),
        events("<script>if (a<b) x()</script>"))

    @Test fun quote_aware_tag_end() = assertEquals(listOf("<a title=x>y>", "</a>"), events("""<a title="x>y"></a>"""))

    @Test fun malformed_attributes_reported() =
        assertTrue(events("""<item id="c1" href="good.xhtml"</manifest>""").any { it.startsWith("E:malformed attributes") })

    @Test fun attr_value_offsets() {
        val s = """<a href="x.xhtml#n1">t</a>"""
        var a: Attr? = null
        MarkupScanner.scan(s, object : MarkupVisitor {
            override fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) { a = attrs[0] }
        })
        assertEquals("x.xhtml#n1", s.substring(a!!.valueStart, a!!.valueEnd))
    }
}
