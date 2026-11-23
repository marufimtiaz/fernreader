package app.fernreader.core.epub.markup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathsTest {
    @Test fun resolves_relative() = assertEquals("OEBPS/Text/ch1.xhtml", Paths.resolve("OEBPS/Text", "ch1.xhtml#x"))
    @Test fun parent_segments() = assertEquals("OEBPS/Images/a.png", Paths.resolve("OEBPS/Text", "../Images/a.png"))
    @Test fun never_above_root() = assertEquals("a.png", Paths.resolve("", "../../a.png"))
    @Test fun percent_encoded_href_resolves() = assertEquals("OEBPS/Livres Été.xhtml", Paths.resolve("OEBPS", "Livres%20%C3%89t%C3%A9.xhtml"))
    @Test fun fragment() { assertEquals("n5", Paths.fragmentOf("notes.xhtml#n5")); assertNull(Paths.fragmentOf("notes.xhtml")) }
    @Test fun external() {
        assertTrue(Paths.isExternal("https://x.org/")); assertTrue(Paths.isExternal("mailto:a@b"))
        assertFalse(Paths.isExternal("c.xhtml")); assertFalse(Paths.isExternal("../a:b.xhtml"))
    }
    @Test fun lone_percent_stays_literal() = assertEquals("100%", Paths.percentDecode("100%"))
    @Test fun dir_of() { assertEquals("OEBPS", Paths.dirOf("OEBPS/content.opf")); assertEquals("", Paths.dirOf("content.opf")) }
}
