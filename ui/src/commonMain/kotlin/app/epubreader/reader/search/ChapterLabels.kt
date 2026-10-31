package app.epubreader.reader.search

import app.epubreader.reader.bridge.TocItem
import app.epubreader.ui.Copy

/**
 * Chapter name for a spine index (F-050 groups hits by chapter): the label of the contents entry with the greatest
 * section index at or before it; entries at the same index resolve to the later one in contents order.
 */
class ChapterLabels(toc: List<TocItem>) {
    // Stable sort: equal indexes keep contents order, so the last of a run is the later entry.
    private val entries = toc.filter { it.index >= 0 }.sortedBy { it.index }

    fun label(spineIndex: Int): String? {
        var lo = 0
        var hi = entries.size // first entry with index > spineIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].index <= spineIndex) lo = mid + 1 else hi = mid
        }
        return entries.getOrNull(lo - 1)?.label?.ifBlank { Copy.untitledEntry }
    }
}
