package app.fernreader.reader

import app.fernreader.core.epub.PositionMap
import app.fernreader.core.epub.TextPos
import app.fernreader.core.epub.pages.BookPages
import app.fernreader.reader.bridge.FromWeb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * C-083 book pages for the open book: the indicator, the slider preview and go-to, from the book's [BookPages] and the
 * reader's visible range (relocate text offsets, virtual sections mapped to the original spine through [map], so a
 * chunked document gets the unchunked book's pages).
 *
 * The page shown is the last page starting at or before the first visible character. A pin keeps a number on screen
 * while the place it was taken from is still visible: after "go to page" (the page's start, which the landed screen
 * shows, though the screen may begin on the page before) and across a layout change (text size, margins, mode, a
 * rotation: the reader keeps the first visible text on screen), so those never change the number. Any relocate whose
 * visible range no longer holds the pinned place drops the pin. Main thread only.
 */
class BookPageNumbers {
    private val _state = MutableStateFlow(NONE)
    val state: StateFlow<PageIndicator> = _state

    var pages: BookPages? = null
        private set
    private var map: PositionMap = PositionMap.Identity
    private var at: FromWeb.Relocate? = null
    private class Pin(val index: Int, val pos: TextPos)
    private var pin: Pin? = null

    fun reset() { pages = null; map = PositionMap.Identity; at = null; pin = null; _state.value = NONE }

    fun setPages(p: BookPages?, map: PositionMap) { pages = p; this.map = map; recompute() }

    fun onRelocate(m: FromWeb.Relocate) { at = m; recompute() }

    /** A layout change is coming: the number shown now stays while its first visible character is on screen. */
    fun pinHere() {
        val p = pages ?: return
        val r = at ?: return
        val start = startOf(r)
        if (pin?.let { visible(r, it.pos) } != true) pin = Pin(p.indexAt(start), start)
    }

    /**
     * Go to page: the page [input] names (a label as published, or a number for virtual pages; see BookPages.find),
     * pinned, and where to jump (virtual section and text offset); null if the book has no such page.
     */
    fun goTo(input: String): Pair<Int, TextPos>? {
        val p = pages ?: return null
        val i = p.find(input, from = _state.value.page?.minus(1) ?: -1) ?: return null
        pin = Pin(i, p.start(i))
        return i to map.textToVirtual(p.start(i))
    }

    /** The label of the page at [p] (original coordinates), as the lists show it; null without pages. */
    fun labelAt(p: TextPos): String? = pages?.let { it.label(it.indexAt(p)) }

    /** C-073 slider preview: the page at [sectionFraction] of virtual section [section] (text proportional to the fraction). */
    fun previewAt(section: Int, sectionFraction: Double): PageIndicator {
        val p = pages ?: return NONE
        val start = map.textToOriginal(TextPos(section, 0))
        val next = map.textToOriginal(TextPos(section + 1, 0)).takeIf { it.spineIndex == start.spineIndex && it.offset > start.offset }
        val end = next?.offset ?: p.lengths.getOrElse(start.spineIndex) { start.offset }
        val off = start.offset + ((end - start.offset).coerceAtLeast(0) * sectionFraction.coerceIn(0.0, 1.0)).roundToInt()
        return indicator(p, p.indexAt(TextPos(start.spineIndex, off.coerceAtMost((end - 1).coerceAtLeast(start.offset)))))
    }

    private fun recompute() {
        val p = pages
        val r = at
        if (p == null || r == null) { _state.value = NONE; return }
        val pinned = pin?.takeIf { visible(r, it.pos) }
        pin = pinned
        _state.value = indicator(p, pinned?.index ?: p.indexAt(startOf(r)))
    }

    private fun startOf(r: FromWeb.Relocate) = map.textToOriginal(TextPos(r.sectionIndex, r.textOffset.coerceAtLeast(0)))

    /** [pos] (original) lies in the visible range of [r] (start and end inclusive; a range without text offsets holds nothing). */
    private fun visible(r: FromWeb.Relocate, pos: TextPos): Boolean {
        if (r.textOffset < 0 || r.textEnd < 0) return false
        val a = map.textToOriginal(TextPos(r.sectionIndex, r.textOffset))
        val b = map.textToOriginal(TextPos(r.sectionIndex, r.textEnd))
        return pos.spineIndex == a.spineIndex && pos.offset >= a.offset && pos.offset <= b.offset
    }

    private fun indicator(p: BookPages, index: Int) =
        PageIndicator(index + 1, p.count, exact = true, chapterScope = false, label = if (p.isPrint) p.label(index) else null)

    companion object { val NONE = PageIndicator(null, null, false, false) }
}
