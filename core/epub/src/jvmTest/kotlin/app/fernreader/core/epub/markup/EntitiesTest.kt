package app.fernreader.core.epub.markup

import kotlin.test.Test
import kotlin.test.assertEquals

class EntitiesTest {
    @Test fun named_numeric_and_unknown() =
        assertEquals("Entity test — &bogus; and a stray & ampersand é é", Entities.decode("Entity&nbsp;test &mdash; &bogus; and a stray & ampersand &eacute; &#xE9;"))
    @Test fun crlf_and_cr_become_lf() = assertEquals("a\nb\nc", Entities.decode("a\r\nb\rc"))
    @Test fun char_ref_cr_is_kept() = assertEquals("a\rb", Entities.decode("a&#13;b"))
    @Test fun table_is_complete() {
        assertEquals(253, HtmlEntities.map.size)
        assertEquals(0x20AC, HtmlEntities.map["euro"]); assertEquals(0x2026, HtmlEntities.map["hellip"]); assertEquals(0x27, HtmlEntities.map["apos"])
        assertEquals(0xFF, HtmlEntities.map["yuml"]); assertEquals(0x2666, HtmlEntities.map["diams"]); assertEquals(0x203A, HtmlEntities.map["rsaquo"])
    }
    @Test fun escape_attr() = assertEquals("a&amp;b&quot;c&lt;", Entities.escapeAttr("a&b\"c<"))
}
