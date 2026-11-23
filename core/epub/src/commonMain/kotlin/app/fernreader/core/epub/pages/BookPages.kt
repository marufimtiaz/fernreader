package app.fernreader.core.epub.pages

import app.fernreader.core.epub.TextPos

/** Where a book's page numbers come from (C-083): a print page list of the book, or fixed virtual pages. */
enum class PageSource {
    /** EPUB 3 navigation document, `nav` of type `page-list`. */
    NAV,
    /** EPUB 2 NCX `pageList`. */
    NCX,
    /** Adobe `page-map.xml`. */
    PAGE_MAP,
    /** `epub:type="pagebreak"` / `role="doc-pagebreak"` markers in the text, when no list names them. */
    MARKERS,
    /** No print pages: [BookPages.VIRTUAL_CHARS] characters of text per page. */
    VIRTUAL,
    /** C-087: a fixed-layout book without a print page list: its own pages, one per spine item, numbered 1..N. */
    FIXED,
}

/**
 * Book pages (C-083): page numbers that do not depend on layout. Page `i` (0-based) starts at
 * ([spines] `[i]`, [offsets] `[i]`), a position in the original spine's canonical text (TextExtractor, UTF-16), in
 * reading order; the page at a position is the last page starting at or before it (the first page also covers
 * anything before it). [labels] are the print labels as published (null for virtual pages, numbered 1..[count]).
 * [lengths] is the canonical text length of every spine item (where a fraction of a section lands, the slider preview).
 */
class BookPages(
    val source: PageSource,
    val labels: List<String>?,
    private val spines: IntArray,
    private val offsets: IntArray,
    val lengths: IntArray,
) {
    init {
        require(spines.size == offsets.size && spines.isNotEmpty()) { "pages need starts" }
        require(labels == null || labels.size == spines.size) { "one label per page" }
    }

    val count: Int get() = spines.size
    /** Labels as published (a print page list or markers), else numbers 1..[count]. */
    val isPrint: Boolean get() = labels != null

    fun label(index: Int): String = labels?.get(index) ?: (index + 1).toString()
    fun start(index: Int): TextPos = TextPos(spines[index], offsets[index])

    /** The page at [p] (original coordinates): the last one starting at or before it; 0 before the first. */
    fun indexAt(p: TextPos): Int {
        var lo = 0
        var hi = count - 1
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compare(spines[mid], offsets[mid], p.spineIndex, p.offset)
            if (c <= 0) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    /**
     * The page the reader means by [input] ("go to page"): a label as published, compared without case and with
     * whitespace collapsed ("XII" finds "xii"); for virtual pages a number 1..[count]. A label used more than once
     * (a book that numbers each part anew) gives the first one after [from], else the first one. Null if none.
     */
    fun find(input: String, from: Int = -1): Int? {
        val q = normalize(input)
        if (q.isEmpty()) return null
        val ls = labels ?: return q.toIntOrNull()?.takeIf { it in 1..count }?.minus(1)
        val hits = ls.indices.filter { normalize(ls[it]) == q }
        return hits.firstOrNull { it > from } ?: hits.firstOrNull()
    }

    /** Stored form (schema 8 `book_pages` columns): starts as "spine.offset" joined by ",", lengths by ",". */
    fun encodeStarts(): String = buildString(count * 8) {
        for (i in 0 until count) { if (i > 0) append(','); append(spines[i]).append('.').append(offsets[i]) }
    }
    fun encodeLabels(): String? = labels?.joinToString("\n")
    fun encodeLengths(): String = lengths.joinToString(",")

    override fun equals(other: Any?): Boolean = other is BookPages && source == other.source && labels == other.labels &&
        spines.contentEquals(other.spines) && offsets.contentEquals(other.offsets) && lengths.contentEquals(other.lengths)
    override fun hashCode(): Int = 31 * spines.contentHashCode() + offsets.contentHashCode()
    override fun toString(): String = "BookPages($source, $count pages)"

    companion object {
        /** C-083 (2): characters of text per virtual page, whitespace runs counted as one. */
        const val VIRTUAL_CHARS = 1800
        /**
         * Bump when the pages a book gets change: stored pages of another version are computed again.
         * 2: C-087, fixed-layout books get one page per spine item (version 1 stored about one virtual page).
         */
        const val VERSION = 2

        private fun compare(s1: Int, o1: Int, s2: Int, o2: Int) = if (s1 != s2) s1.compareTo(s2) else o1.compareTo(o2)
        private val WS = Regex("\\s+")
        internal fun normalize(s: String) = WS.replace(s, " ").trim().lowercase()

        /** From the stored columns; null if they do not decode (the pages are then computed again). */
        fun decode(source: String, labels: String?, starts: String, lengths: String): BookPages? = try {
            val src = PageSource.valueOf(source)
            val parts = starts.split(',')
            val sp = IntArray(parts.size)
            val of = IntArray(parts.size)
            for ((i, p) in parts.withIndex()) {
                val dot = p.indexOf('.')
                sp[i] = p.substring(0, dot).toInt(); of[i] = p.substring(dot + 1).toInt()
            }
            val ls = labels?.split('\n')
            val len = if (lengths.isEmpty()) IntArray(0) else lengths.split(',').map { it.toInt() }.toIntArray()
            val numbered = src == PageSource.VIRTUAL || src == PageSource.FIXED
            if (numbered && ls != null || !numbered && ls == null) null
            else BookPages(src, ls, sp, of, len)
        } catch (e: Exception) {
            null
        }

        /**
         * C-087: the pages of a fixed-layout book without a print page list: page i is spine item i, numbered 1..[count].
         * Also what the reader shows for such a book before its pages are computed ([lengths] then unknown: zeros).
         */
        fun fixedLayout(count: Int, lengths: IntArray = IntArray(count)): BookPages =
            BookPages(PageSource.FIXED, null, IntArray(count) { it }, IntArray(count), lengths)

        /** Virtual pages only (tests and the builder). */
        internal fun of(source: PageSource, labels: List<String>?, starts: List<TextPos>, lengths: IntArray) =
            BookPages(source, labels, IntArray(starts.size) { starts[it].spineIndex }, IntArray(starts.size) { starts[it].offset }, lengths)
    }
}
