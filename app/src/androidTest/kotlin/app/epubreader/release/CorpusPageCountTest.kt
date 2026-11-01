package app.epubreader.release

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.epubreader.MainActivity
import app.epubreader.core.data.ReaderSettings
import app.epubreader.reader.PageIndicator
import app.epubreader.reader.ReaderHarness
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.js
import app.epubreader.reader.warAndPeace
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M9 release check (C-003, C-016, C-020): over the whole corpus, at the default settings,
 * - every linear section's counted pages equal the pages the reader turns through (agreement; vertical-writing
 *   books, which show per-chapter totals, are checked the same way);
 * - every "≈ N" the indicator showed for a reflowable book with a book-wide total is within ±5 % of the exact total.
 * Books with more than [MAX_SECTIONS] linear sections are checked on that many evenly spaced sections (always the
 * first and the last). Fixed-layout books have no text pages and are skipped. Malformed books are R-002's.
 *
 * Long (~20 min): run it on its own, `-e class app.epubreader.release.CorpusPageCountTest` (docs/milestones/M9.md).
 * The per-book table is written to logcat (tag EPR) and to /sdcard/Download/corpus-pagecount.txt.
 */
@RunWith(AndroidJUnit4::class)
class CorpusPageCountTest {
    private val roots = listOf("/sdcard/Books/gutenberg", "/sdcard/Books/epub3-samples", "/sdcard/Books/generated")

    private fun books(): List<File> = roots.flatMap { r -> File(r).listFiles { f -> f.name.endsWith(".epub") }?.sortedBy { it.name }.orEmpty() }
        .filterNot { it.name.startsWith("malformed-") || it.name == "fake.epub" }

    @Test fun whole_corpus_counts_agree_and_estimates_are_within_5_percent(): Unit = runBlocking {
        warAndPeace() // storage access + corpus present
        val books = books()
        check(books.size >= 30) { "corpus not pushed: ${books.size} books" }
        val rows = mutableListOf<String>()
        val problems = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
            try {
                h.ready()
                for (book in books) {
                    val r = runCatching { checkBook(sc, h, book) }.getOrElse { e -> BookResult(book.name, error = "${e::class.simpleName}: ${e.message}") }
                    rows += r.row(); problems += r.problems()
                    Log.i("EPR", "corpus pagecount: ${r.row()}")
                    sc.onActivity { it.goBack() }
                    delay(500)
                }
            } finally { h.stop() }
        }
        val table = (listOf("book | fixed | scope | sections checked | exact total | worst estimate error % | mismatches | note") + rows).joinToString("\n")
        runCatching { File("/sdcard/Download/corpus-pagecount.txt").writeText(table + "\n\nproblems:\n" + problems.joinToString("\n") + "\n") }
        Log.i("EPR", "corpus pagecount problems: $problems")
        assertEquals(emptyList<String>(), problems)
    }

    private class BookResult(
        val name: String, val fixed: Boolean = false, val chapterScope: Boolean = false, val checked: Int = 0, val total: Int? = null,
        val worstPct: Double? = null, val mismatches: List<String> = emptyList(), val error: String? = null,
    ) {
        fun row() = "$name | $fixed | ${if (chapterScope) "chapter" else "book"} | $checked | ${total ?: "-"} | " +
            "${worstPct?.let { "%.2f".format(it) } ?: "-"} | ${mismatches.size} | ${error ?: mismatches.take(3).joinToString("; ")}"
        fun problems(): List<String> = buildList {
            error?.let { add("$name: $it") }
            mismatches.forEach { add("$name: $it") }
            if ((worstPct ?: 0.0) > 5.0) add("$name: estimate off by %.2f %% (exact $total)".format(worstPct))
        }
    }

    private suspend fun checkBook(sc: ActivityScenario<MainActivity>, h: ReaderHarness, book: File): BookResult = kotlinx.coroutines.coroutineScope {
        val shown = mutableListOf<PageIndicator>()
        var opened: FromWeb.Opened? = null
        val seen = launch(start = CoroutineStart.UNDISPATCHED) { h.a.controller.page.collect { shown += it } }
        val ev = launch(start = CoroutineStart.UNDISPATCHED) { h.a.reader.events.collect { if (it is FromWeb.Opened) opened = it } }
        try {
            h.drain()
            h.open(book, withToc = false)
            val o = withTimeout(10_000) { while (opened == null) delay(50); opened!! }
            if (o.fixedLayout) return@coroutineScope BookResult(book.name, fixed = true)
            val exact = withTimeoutOrNull(180_000) { h.a.controller.page.first { it.exact } }
                ?: return@coroutineScope BookResult(book.name, error = "no exact total within 180 s")
            val scope = exact.chapterScope
            val linear = JSONArray(JSONTokener(sc.js("JSON.stringify(window.__readerTest.linear())")).nextValue() as String)
                .let { a -> List(a.length()) { a.getInt(it) } }
            val pick = if (linear.size <= MAX_SECTIONS) linear
            else (0 until MAX_SECTIONS).map { linear[it * (linear.size - 1) / (MAX_SECTIONS - 1)] }.distinct()
            val wrong = mutableListOf<String>()
            for (i in pick) {
                h.drain()
                sc.js("window.__readerTest.goIndex($i)")
                h.relocate(15_000) { it.sectionIndex == i && it.pages > 0 }
                var visible = -1
                var tries = 0
                while (tries++ < 30) { // two equal readings 150 ms apart (fonts and images settle after the first relocate)
                    delay(150)
                    val p = JSONObject(JSONTokener(sc.js("JSON.stringify(window.__readerTest.page())")).nextValue() as String).getInt("textPages")
                    if (p == visible) break
                    visible = p
                }
                val counted = h.a.controller.countedPages(i)
                if (counted != visible) wrong += "section $i: counted $counted, visible $visible"
            }
            val total = exact.total
            val worst = if (scope || total == null || total == 0) null
            else shown.filter { !it.exact && !it.chapterScope && it.total != null }.maxOfOrNull { kotlin.math.abs(it.total!! - total) * 100.0 / total } ?: 0.0
            BookResult(book.name, false, scope, pick.size, total, worst, wrong)
        } finally { seen.cancel(); ev.cancel() }
    }

    private companion object { const val MAX_SECTIONS = 40 }
}
