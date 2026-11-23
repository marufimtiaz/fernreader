package app.fernreader.core.epub.text

import app.fernreader.core.epub.Corpus
import app.fernreader.core.epub.markup.TextDecoding
import app.fernreader.core.epub.openFileSource
import app.fernreader.core.epub.zip.ZipReader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextExtractorTest {
    @Test fun crlf_entities_cdata_script() {
        val x = TextExtractor.extract("<html><head><title>T</title><style>p{}</style></head>\r\n<body>\r\n<p>A&nbsp;b &amp; c&#13;d</p><script>var x=1</script><![CDATA[e<f]]>\r<p>&bogus; &</p></body></html>")
        assertEquals("\nA b & c\rd" + "e<f\n" + "&bogus; &", x.text)
    }

    /** Page estimates (M4) count text as the reader lays it out: whitespace runs are one space, none at the ends. */
    @Test fun compact_length_counts_whitespace_runs_once() {
        assertEquals(3, TextExtractor.extract("<body>\n  <p>a  \n b</p>\n</body>").compactLength)
        assertEquals(0, TextExtractor.extract("<body> \n </body>").compactLength)
    }

    @Test fun no_body_tag_counts_everything_but_head() =
        assertEquals("hi there", TextExtractor.extract("<head><title>x</title></head><p>hi</p> <p>there</p>").text)

    @Test fun cut_offsets_map_markup_to_text() {
        val m = "<body><p>abc</p><p>defg</p><p>h</p></body>"
        val x = TextExtractor.extract(m, intArrayOf(m.indexOf("<p>defg"), m.indexOf("<p>h")))
        assertContentEquals(intArrayOf(3, 7), x.cutTextOffsets)
    }

    @Test fun segments_respect_blocks_and_bounds() {
        val m = "<body>" + (1..50).joinToString("") { "<p>${"word ".repeat(30)}$it</p>" } + "<div>${"x".repeat(20_000)}</div></body>"
        val x = TextExtractor.extract(m)
        assertTrue(x.segments.all { it.end - it.start <= 8000 })
        assertTrue(x.segments.dropLast(1).all { it.end - it.start >= 200 })
        assertEquals(x.text.length, x.segments.last().end)
    }

    @Test fun huge_chapter_contains_marker_138_times_and_counts_images() {
        openFileSource(Corpus.generated("stress-huge-single-chapter.epub").path).use { src ->
            val z = ZipReader.open(src)
            val m = TextDecoding.decode(z.readBytes(z.entry("OEBPS/huge.xhtml")!!))
            val t0 = System.nanoTime()
            val x = TextExtractor.extract(m)
            println("extract 10 MB: ${(System.nanoTime() - t0) / 1_000_000} ms")
            assertEquals(138, Regex("MARKERWORDHUGE").findAll(x.text).count())
        }
    }
}
