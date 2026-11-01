package app.epubreader.reader

import app.epubreader.core.data.IndicatorScope
import app.epubreader.reader.bridge.FromWeb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageTotalsTest {
    // 6 sections: 0 cover (1 image), 1..4 text, 5 non-linear notes
    private val stats = listOf(SectionStat(0, 1), SectionStat(2_000, 0), SectionStat(4_000, 0), SectionStat(1_000, 0), SectionStat(3_000, 0), SectionStat(500, 0))
    private val linear = listOf(0, 1, 2, 3, 4)
    private fun counts(gen: Int, vararg c: Pair<Int, Int>, sample: Boolean = false, done: Boolean = false, linear: List<Int>? = null) =
        FromWeb.PageCounts(sessionId = "s", gen = gen, linear = linear, counts = c.map { listOf(it.first, it.second) }, sample = sample, done = done)
    private fun at(i: Int, page: Int, pages: Int, f: Double = 0.0) =
        FromWeb.Relocate(sessionId = "s", cfi = "c", fraction = 0.0, sectionIndex = i, page = page, pages = pages, sectionFraction = f)
    private fun totals() = PageTotals().apply { setStats(stats, 6) }

    @Test fun page_number_without_total_before_the_sample() {
        val t = totals()
        t.onCounts(counts(1, linear = linear))
        t.onRelocate(at(2, 3, 8)) // 4000 chars on 8 pages: C = 4000 / 7.5
        val s = t.state.value
        assertNull(s.total)
        // before: cover 1 (images) + section 1: round(2000 / 533.3 + 0.5) = 4 -> page 1 + 4 + 3 = 8
        assertEquals(8, s.page)
    }

    @Test fun estimate_after_the_sample_uses_the_sample_calibration() {
        val t = totals()
        t.onCounts(counts(1, linear = linear))
        t.onRelocate(at(2, 3, 8))
        t.onCounts(counts(1, 1 to 4, 4 to 7, sample = true)) // C = (2000 + 3000) / (3.5 + 6.5) = 500
        val s = t.state.value
        // 0: 1 (image), 1: 4 (counted), 2: 8 (visible), 3: round(1000/500 + 0.5) = 3 (2.5 rounds half up), 4: 7 (counted)
        assertEquals(1 + 4 + 8 + 3 + 7, s.total)
        assertEquals(false, s.exact)
        assertEquals(1 + 4 + 3, s.page)
    }

    @Test fun exact_once_every_section_is_counted() {
        val t = totals()
        t.onCounts(counts(1, linear = linear))
        t.onRelocate(at(2, 3, 8))
        t.onCounts(counts(1, 1 to 4, 4 to 7, sample = true))
        t.onCounts(counts(1, 0 to 1, 2 to 8, 3 to 2, done = true))
        assertEquals(PageIndicator(page = 1 + 4 + 3, total = 22, exact = true, chapterScope = false), t.state.value)
    }

    @Test fun preview_of_a_seek_target_uses_the_same_counts() {
        val t = totals()
        t.onCounts(counts(1, linear = linear))
        t.onRelocate(at(2, 3, 8))
        t.onCounts(counts(1, 0 to 1, 1 to 4, 2 to 8, 3 to 2, 4 to 7, sample = true, done = true))
        // section 4 (7 pages), halfway: page 1 + 4 + 8 + 2 + floor(0.5 * 7) + 1
        assertEquals(PageIndicator(page = 1 + 4 + 8 + 2 + 4, total = 22, exact = true, chapterScope = false), t.previewAt(4, 0.5))
        // the visible section: its own pages; the end of a section is its last page
        assertEquals(1 + 4 + 8, t.previewAt(2, 1.0).page)
        assertEquals(PageIndicator(page = 1 + 4 + 3, total = 22, exact = true, chapterScope = false), t.state.value) // unchanged
        t.scope = IndicatorScope.CHAPTER
        assertEquals(PageIndicator(page = 2, total = 4, exact = true, chapterScope = true), t.previewAt(1, 0.3))
    }

    @Test fun preview_without_a_location_is_empty() =
        assertEquals(PageIndicator(null, null, false, false), totals().previewAt(1, 0.5))

    @Test fun image_only_section_counts_its_images() {
        val t = PageTotals().apply { setStats(listOf(SectionStat(10, 3), SectionStat(1_000, 0)), 2) }
        t.onCounts(counts(1, linear = listOf(0, 1)))
        t.onRelocate(at(1, 1, 3))
        t.onCounts(counts(1, 1 to 3, sample = true))
        assertEquals(3 + 3, t.state.value.total)
    }

    @Test fun stale_generation_is_ignored() {
        val t = totals()
        t.onCounts(counts(2, linear = linear))
        t.onRelocate(at(2, 3, 8))
        t.onCounts(counts(1, 0 to 1, 1 to 4, 2 to 8, 3 to 2, 4 to 7, sample = true, done = true)) // an older layout
        assertNull(t.state.value.total)
        t.onCounts(counts(3, linear = linear)) // newer layout: earlier counts are gone
        t.onCounts(counts(2, 1 to 4, 4 to 7, sample = true))
        assertNull(t.state.value.total)
    }

    @Test fun stats_size_mismatch_disables_estimate() {
        val t = PageTotals().apply { setStats(stats, 7) } // Kotlin spine != foliate sections
        t.onCounts(counts(1, linear = linear))
        t.onRelocate(at(2, 3, 8))
        t.onCounts(counts(1, 1 to 4, 4 to 7, sample = true))
        assertEquals(PageIndicator(null, null, false, false), t.state.value)
        t.onCounts(counts(1, 0 to 1, 2 to 8, 3 to 2, done = true))
        assertEquals(PageIndicator(8, 22, true, false), t.state.value) // exact counts need no stats
    }

    @Test fun scrolled_mode_shows_the_paged_number_at_the_same_place() {
        val t = totals()
        t.onCounts(counts(1, linear = linear))
        t.onCounts(counts(1, 0 to 1, 1 to 4, 2 to 8, 3 to 2, 4 to 7, sample = true, done = true))
        t.onRelocate(at(2, 0, 0, f = 0.5)) // C-002: half way through an 8-page section = its page 5
        assertEquals(1 + 4 + 5, t.state.value.page)
    }

    @Test fun chapter_scope_counts_inside_the_section() {
        val t = totals()
        t.scope = IndicatorScope.CHAPTER
        t.onRelocate(at(2, 3, 8))
        assertEquals(PageIndicator(3, 8, true, true), t.state.value)
    }

    /** S1: until the sample of a new layout is in, the previous layout's counts scaled by the visible section's ratio. */
    @Test fun new_layout_scales_previous_counts_until_its_sample() {
        val t = totals()
        t.onCounts(counts(1, linear = linear))
        t.onCounts(counts(1, 0 to 1, 1 to 4, 2 to 8, 3 to 2, 4 to 7, sample = true, done = true))
        t.onRelocate(at(2, 3, 8))
        assertEquals(1 + 4 + 3, t.state.value.page)
        t.onCounts(counts(2, linear = linear)) // text 50 % larger: the visible section now has 12 pages
        t.onRelocate(at(2, 5, 12))
        val s = t.state.value
        assertNull(s.total) // C-017: no total before the new sample
        assertEquals(2 + 6 + 5, s.page) // cover 1 * 1.5 -> 2 (rounded), section 1: 4 * 1.5 = 6, then page 5
    }

    /** S1 decision 2: with an earlier layout's counts, the sample gives a ratio for those counts (not the text model). */
    @Test fun sample_scales_previous_counts_when_they_exist() {
        val t = totals()
        t.onCounts(counts(1, linear = linear))
        t.onCounts(counts(1, 0 to 1, 1 to 4, 2 to 8, 3 to 2, 4 to 7, sample = true, done = true))
        t.onCounts(counts(2, linear = linear))
        t.onRelocate(at(2, 5, 12))
        t.onCounts(counts(2, 1 to 6, 4 to 10, sample = true)) // ratio (6 + 10) / (4 + 7) = 1.4545
        val s = t.state.value
        // 0: round(1 * 1.4545) = 1, 1: 6, 2: 12 (visible), 3: round(2 * 1.4545) = 3, 4: 10
        assertEquals(1 + 6 + 12 + 3 + 10, s.total)
        assertEquals(1 + 6 + 5, s.page)
    }
}
