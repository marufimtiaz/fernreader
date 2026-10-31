package app.epubreader.core.epub.text

import app.epubreader.core.epub.markup.Attr
import app.epubreader.core.epub.markup.Entities
import app.epubreader.core.epub.markup.MarkupScanner
import app.epubreader.core.epub.markup.MarkupVisitor

data class Segment(val start: Int, val end: Int)

class ExtractedText(val text: String, val segments: List<Segment>, val imageCount: Int, val cutTextOffsets: IntArray) {
    /** Length with every whitespace run counted as one character and none at the ends (page estimates, F-034). */
    val compactLength: Int get() {
        var n = 0
        var gap = false
        for (c in text) {
            if (c.isWhitespace()) { if (n > 0) gap = true } else { if (gap) n++; gap = false; n++ }
        }
        return n
    }
}

/** Canonical plain text of a content document; see the rules in the M2 plan / docs/bridge-protocol.md. */
object TextExtractor {
    /** Bump when the output changes: derived data built from it (the search index) is rebuilt. */
    const val VERSION = 1
    const val MIN_SEGMENT = 200
    const val MAX_SEGMENT = 8000
    private val SKIP = setOf("script", "style", "template", "head")
    private val BLOCK = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "td", "th", "tr",
        "dt", "dd", "section", "article", "aside", "figcaption", "figure", "table", "ul", "ol", "header", "footer", "nav", "br", "hr")
    private val IMAGES = setOf("img", "image", "svg")

    fun extract(markup: String, cuts: IntArray = IntArray(0)): ExtractedText {
        val hasBody = MarkupScanner.indexOfIgnoreCase(markup, "<body", 0, markup.length) >= 0
        val sb = StringBuilder(markup.length / 2)
        val bounds = ArrayList<Int>()
        val cutOut = IntArray(cuts.size)
        var nextCut = 0
        var inBody = !hasBody
        var skip = 0
        var images = 0
        fun mark(pos: Int) { while (nextCut < cuts.size && cuts[nextCut] <= pos) cutOut[nextCut++] = sb.length }

        MarkupScanner.scan(markup, object : MarkupVisitor {
            override fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) {
                mark(start)
                val n = name.substringAfter(':').lowercase()
                if (n == "body") { inBody = true; return }
                if (n in SKIP && !selfClosing) skip++
                if (n in BLOCK) bounds += sb.length
                if (n in IMAGES && inBody && skip == 0) images++
            }
            override fun endTag(name: String, start: Int, end: Int) {
                mark(start)
                val n = name.substringAfter(':').lowercase()
                if (n == "body") { inBody = false; return }
                if (n in SKIP && skip > 0) skip--
                if (n in BLOCK) bounds += sb.length
            }
            override fun text(start: Int, end: Int) {
                mark(start)
                if (inBody && skip == 0) Entities.appendDecoded(markup, start, end, sb)
            }
            override fun cdata(start: Int, end: Int) {
                mark(start)
                if (!inBody || skip != 0) return
                // CDATA is literal: only newline normalization applies.
                var i = start
                while (i < end) {
                    val c = markup[i]
                    if (c == '\r') { sb.append('\n'); if (i + 1 < end && markup[i + 1] == '\n') i++ } else sb.append(c)
                    i++
                }
            }
        })
        mark(Int.MAX_VALUE)
        val text = sb.toString()
        return ExtractedText(text, segments(text, bounds), images, cutOut)
    }

    private fun segments(text: String, bounds: List<Int>): List<Segment> {
        val out = ArrayList<Segment>()
        fun emit(a: Int, b: Int) {
            var s = a
            while (b - s > MAX_SEGMENT) {
                // Prefer a space in the second half of the window so no piece ends up tiny.
                val ws = text.lastIndexOf(' ', s + MAX_SEGMENT)
                val cut = if (ws >= s + MAX_SEGMENT / 2) ws + 1 else s + MAX_SEGMENT
                if (text.substring(s, cut).isNotBlank()) out += Segment(s, cut)
                s = cut
            }
            if (b > s && text.substring(s, b).isNotBlank()) out += Segment(s, b)
        }
        var start = 0
        for (b in bounds.asSequence().plus(text.length).distinct().sorted()) {
            if (b - start >= MIN_SEGMENT || b == text.length) { emit(start, b); start = b }
        }
        return out
    }
}
