package app.epubreader.reader

import app.epubreader.core.data.IndicatorScope
import app.epubreader.reader.bridge.FromWeb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** What the page indicator shows: "page N", "page N of ≈ M" or "page N of M" (F-034, C-016, C-017). */
data class PageIndicator(val page: Int?, val total: Int?, val exact: Boolean, val chapterScope: Boolean)

/**
 * Page number and book total for the open book (F-034, P-021). Counts come from the web side's hidden-layout counter
 * per layout generation; section text sizes come from Kotlin (S1 text model):
 * with an earlier layout's complete counts, those scaled by the ratio new/old over the sample; otherwise
 * `C = Σchars / Σ(pages − 0.5)` over counted sections with ≥ [MIN_CHARS] characters, an uncounted section then has
 * `round(chars / C + 0.5)` pages (≥ 1), or as many pages as images when it has almost no text.
 * - Before the stratified sample: the previous layout's counts scaled by the visible section's ratio, or (no earlier
 *   count) C from the visible section alone; the page number is shown, the total is not (C-017).
 * - After the sample: page N of ≈ M. When every section is counted: exact (C-003).
 * The visible section's own page count (paged mode) always wins over its counted value; a difference is a bug (C-003)
 * and is reported to [probe].
 * Main thread only.
 */
class PageTotals(private val probe: EventProbe = EventProbe.None) {
    private val _state = MutableStateFlow(PageIndicator(null, null, false, false))
    val state: StateFlow<PageIndicator> = _state

    var scope: IndicatorScope = IndicatorScope.BOOK
        set(v) { field = v; recompute() }

    private var stats: List<SectionStat>? = null
    private var gen = -1
    private var linear: List<Int> = emptyList()
    private val counts = HashMap<Int, Int>()
    /** The last complete count (an earlier layout): scaled until a new layout has its sample (S1 "scale"). */
    private var previous: Map<Int, Int> = emptyMap()
    private val sampled = HashSet<Int>()
    private var sampleDone = false
    private var done = false
    private var at: FromWeb.Relocate? = null
    private var reportedEstimate = -1
    private var reportedExact = -1

    /** Pages counted for section [index] in the current layout, if counted yet (diagnostics, C-003 agreement test). */
    fun counted(index: Int): Int? = counts[index]

    /** [sections]: foliate's section count; stats for another spine (count mismatch) are not used. */
    fun setStats(stats: List<SectionStat>?, sections: Int) {
        this.stats = stats?.takeIf { it.size == sections }
        recompute()
    }

    fun reset() {
        stats = null; gen = -1; linear = emptyList(); counts.clear(); previous = emptyMap(); sampled.clear(); sampleDone = false; done = false; at = null
        _state.value = PageIndicator(null, null, false, scope == IndicatorScope.CHAPTER)
    }

    fun onCounts(m: FromWeb.PageCounts) {
        if (m.gen < gen) return
        if (m.gen > gen) {
            if (done) previous = HashMap(counts)
            gen = m.gen; counts.clear(); sampled.clear(); sampleDone = false; done = false
            m.linear?.let { linear = it }
        }
        for (c in m.counts) if (c.size == 2) {
            counts[c[0]] = c[1]
            if (m.sample) sampled += c[0]
        }
        if (m.sample) sampleDone = true
        if (m.done) done = true
        recompute()
    }

    fun onRelocate(m: FromWeb.Relocate) {
        at = m
        val counted = counts[m.sectionIndex]
        if (m.pages > 0 && counted != null && counted != m.pages) probe.ev("count_mismatch", "section=${m.sectionIndex} counted=$counted visible=${m.pages}")
        recompute()
    }

    /**
     * C-073: the indicator for section [section] at [sectionFraction] (a seek target), from the same counts and
     * estimates as the visible location's; [state] does not change.
     */
    fun previewAt(section: Int, sectionFraction: Double): PageIndicator {
        val r = at ?: return PageIndicator(null, null, false, scope == IndicatorScope.CHAPTER)
        return indicator(r, section, 0, sectionFraction)
    }

    private fun recompute() {
        val r = at
        if (r == null) { _state.value = PageIndicator(null, null, false, scope == IndicatorScope.CHAPTER); return }
        val ind = indicator(r, r.sectionIndex, r.page, r.sectionFraction)
        _state.value = ind
        val total = ind.total
        if (!ind.chapterScope && total != null) {
            if (done && reportedExact != gen) { reportedExact = gen; probe.ev("page_total_exact", "total=$total gen=$gen") }
            else if (!done && reportedEstimate != gen) { reportedEstimate = gen; probe.ev("page_total_estimate", "total=$total gen=$gen") }
        }
    }

    /** The indicator at [target] ([page] in it if known, else at [sectionFraction]); [r] is the visible location. */
    private fun indicator(r: FromWeb.Relocate, target: Int, page: Int, sectionFraction: Double): PageIndicator {
        val cur = r.sectionIndex
        val visible = r.pages.takeIf { it > 0 }
        val st = stats
        val calibration: Double? = when {
            st == null -> null
            sampleDone -> rate(sampled.filter { (st.getOrNull(it)?.chars ?: 0) >= MIN_CHARS }.map { st[it].chars to counts.getValue(it) })
            visible != null && (st.getOrNull(cur)?.chars ?: 0) >= MIN_CHARS -> rate(listOf(st[cur].chars to visible))
            else -> null
        }
        // An earlier layout's counts, scaled: by the sample's ratio once it is in (S1 decision 2), before that by how the
        // visible section changed. Without earlier counts, the text model.
        val scale: Double? = when {
            previous.isEmpty() -> null
            sampleDone -> sampled.filter { it in previous }.let { s -> if (s.isEmpty()) null else s.sumOf { counts.getValue(it) }.toDouble() / s.sumOf { previous.getValue(it) } }
            visible != null -> previous[cur]?.let { visible.toDouble() / it }
            else -> null
        }
        fun pagesOf(i: Int): Int? {
            if (i == cur && visible != null) return visible
            counts[i]?.let { return it }
            if (scale != null) previous[i]?.let { return max(1, (it * scale).roundToInt()) }
            val s = st?.getOrNull(i) ?: return null
            val c = calibration ?: return null
            return if (s.chars >= MIN_CHARS) max(1, (s.chars / c + 0.5).roundToInt()) else max(1, s.images)
        }
        val targetPages = pagesOf(target)
        val inSection = when {
            page > 0 -> page
            targetPages != null -> (floor(sectionFraction * targetPages).toInt() + 1).coerceAtMost(targetPages) // C-002
            else -> null
        }
        if (scope == IndicatorScope.CHAPTER) {
            return PageIndicator(inSection, targetPages, exact = (target == cur && visible != null) || counts.containsKey(target), chapterScope = true)
        }
        val before = linear.takeIf { target in it }?.filter { it < target }?.map(::pagesOf)
        val n = if (before == null || before.any { it == null } || inSection == null) null else before.sumOf { it!! } + inSection
        val all = linear.map(::pagesOf)
        val total = if ((sampleDone || done) && all.isNotEmpty() && all.none { it == null }) all.sumOf { it!! } else null
        return PageIndicator(n?.let { p -> total?.let { p.coerceIn(1, it) } ?: p }, total, exact = done && total != null, chapterScope = false)
    }

    private fun rate(samples: List<Pair<Int, Int>>): Double? {
        val chars = samples.sumOf { it.first }
        val pages = samples.sumOf { it.second - 0.5 }
        return if (chars > 0 && pages > 0) chars / pages else null
    }

    companion object { const val MIN_CHARS = 200 }
}
