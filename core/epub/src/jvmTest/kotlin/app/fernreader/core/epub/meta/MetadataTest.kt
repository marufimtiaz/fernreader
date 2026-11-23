package app.fernreader.core.epub.meta

import app.fernreader.core.epub.Corpus
import app.fernreader.core.epub.markup.XmlTree
import app.fernreader.core.epub.openFileSource
import app.fernreader.core.epub.pkg.LoadResult
import app.fernreader.core.epub.pkg.PackageLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetadataTest {
    private fun ok(name: String) = openFileSource(Corpus.generated(name).path).use { (PackageLoader.open(it).second as LoadResult.Ok) }

    @Test fun title_author_language() {
        val m = ok("no-cover.epub").pkg.metadata
        assertEquals("No Cover Book", m.title); assertEquals(listOf("Corpus Generator"), m.authors); assertEquals("en", m.language)
    }

    @Test fun epub3_creator_roles_prefer_authors() {
        val opf = XmlTree.parse("""<package xmlns:dc="d"><metadata><dc:creator id="a">Ann</dc:creator><meta refines="#a" property="role">aut</meta><dc:creator id="i">Ian</dc:creator><meta refines="#i" property="role">ill</meta></metadata></package>""").root!!
        assertEquals(listOf("Ann"), MetadataReader.read(opf).authors)
    }

    @Test fun rtl_detection() {
        assertTrue(MetadataReader.isRtl("نظام مضاد للسرطان")); assertTrue(MetadataReader.isRtl("שלום")); assertEquals(false, MetadataReader.isRtl("Alice"))
    }

    @Test fun sort_key_ignores_case_and_leading_article() = assertEquals("whale", MetadataReader.sortKey("The Whale"))

    @Test fun cover_from_epub3_property_or_meta() {
        assertTrue(ok("stress-200-images.epub").pkg.coverPath!!.endsWith(".png"))
        assertTrue(ok("epub2-ncx-only.epub").pkg.coverPath != null)  // EPUB 2 meta name="cover"
    }

    @Test fun no_cover_and_dangling_cover() {
        assertNull(ok("no-cover.epub").pkg.coverPath)
        val r = ok("malformed-missing-files-bad-xml.epub")
        assertTrue(r.warnings.any { it.kind.name == "DANGLING_COVER" })
    }

    // Review Focus 5: a blank title is "no title" (the scanner then uses the file name), not an empty string.
    @Test fun empty_title_is_null() {
        val opf = XmlTree.parse("""<package xmlns:dc="d"><metadata><dc:title>  	 </dc:title></metadata></package>""").root!!
        assertNull(MetadataReader.read(opf).title)
    }

    @Test fun rtl_hebrew_and_arabic_titles() {
        val he = XmlTree.parse("""<package xmlns:dc="d"><metadata><dc:title>ספר הדקדוק</dc:title></metadata></package>""").root!!
        val ar = XmlTree.parse("""<package xmlns:dc="d"><metadata><dc:title>كتاب</dc:title></metadata></package>""").root!!
        assertTrue(MetadataReader.read(he).titleRtl); assertTrue(MetadataReader.read(ar).titleRtl)
    }
}
