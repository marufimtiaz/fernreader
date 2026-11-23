package app.fernreader.reader

import app.fernreader.reader.bridge.TocItem

/** C-073: what the bottom bar shows while the slider is dragged to [fraction]: the chapter and page a release lands on. */
data class SeekPreview(val fraction: Double, val chapter: String?, val page: PageIndicator)

/**
 * The section a seek to [fraction] lands in and the fraction within it, as foliate's SectionProgress.getSection
 * computes it from the same [sizes] (`opened.sizes`: bytes of each linear section, 0 for the others). Null without sizes.
 */
fun sectionAt(sizes: List<Long>, fraction: Double): Pair<Int, Double>? {
    val total = sizes.sum()
    if (sizes.isEmpty() || total <= 0) return null
    if (fraction <= 0) return 0 to 0.0
    if (fraction >= 1) return sizes.lastIndex to 1.0
    val f = fraction + EPSILON
    var index = 0
    var before = 0L // bytes before [index]
    while (index < sizes.lastIndex && (before + sizes[index]).toDouble() / total <= f) { before += sizes[index]; index++ }
    while (sizes[index] == 0L && index < sizes.lastIndex) { before += sizes[index]; index++ }
    return index to ((f - before.toDouble() / total) / (sizes[index].toDouble() / total)).coerceIn(0.0, 1.0)
}

/** JavaScript's Number.EPSILON, which foliate adds before looking the fraction up. */
private const val EPSILON = 2.220446049250313E-16

/**
 * The contents entry for a location: section [section] at [inSection]. One entry in the section: that one. None: the
 * last entry of the nearest section before it (foliate does the same). Several: the one the reader is in when it is
 * [current] (the visible location's href), else the section split evenly between them (their anchors' places are not
 * known before the section is laid out).
 */
fun tocEntryAt(toc: List<TocItem>, section: Int, inSection: Double, current: String? = null): TocItem? {
    val here = toc.filter { it.index == section && !it.missing }
    if (here.isEmpty()) return toc.filter { it.index in 0 until section && !it.missing }.maxByOrNull { it.index }
        ?.let { last -> toc.last { it.index == last.index && !it.missing } }
    here.firstOrNull { it.href == current }?.let { return it }
    return here[(inSection * here.size).toInt().coerceIn(0, here.lastIndex)]
}
