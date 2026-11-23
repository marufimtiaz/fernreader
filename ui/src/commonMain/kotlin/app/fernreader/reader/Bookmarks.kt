package app.fernreader.reader

import kotlin.math.floor
import kotlin.math.roundToInt

/** Tabs of the Contents panel (F-041, F-062): Contents is the default. */
enum class ContentsTab { Contents, Bookmarks, Highlights }

/**
 * The page a bookmark is on, for the list: its book fraction against the book's page total (an estimate, since the
 * fraction follows text size, not page counts), or null when the total is not known or counts a chapter only.
 */
fun bookmarkPage(fraction: Double, indicator: PageIndicator): Int? {
    val total = indicator.total ?: return null
    if (indicator.chapterScope || total <= 0) return null
    return (floor(fraction.coerceIn(0.0, 1.0) * total).toInt() + 1).coerceIn(1, total)
}

/** The list's fallback when no page total is known yet: how far into the book, in whole percent. */
fun bookmarkPercent(fraction: Double): Int = (fraction.coerceIn(0.0, 1.0) * 100).roundToInt()
