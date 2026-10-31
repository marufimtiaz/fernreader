package app.epubreader.core.search

import kotlinx.coroutines.yield

/** Fills a book's [SearchIndex] in spine order, one transaction per section; resumes where a previous run stopped. */
internal object IndexBuilder {
    /**
     * Runs on the caller's thread (give it a background dispatcher) and checks for cancellation between sections.
     * [onProgress] gets the state on open and after every section. Throws [BookUnavailableException] if the book goes
     * away mid-build; what is committed stays valid and a later call continues from it.
     */
    suspend fun build(
        path: String,
        digest: String,
        text: BookText,
        tag: String = SearchIndex.formatTag(),
        onProgress: (IndexProgress) -> Unit = {},
    ) {
        val n = text.sectionCount
        SearchIndex.openOrReset(path, digest, n, tag).use { index ->
            val start = index.meta()
            onProgress(IndexProgress(start.sectionsDone, n, start.complete, running = true, failed = false))
            if (start.complete) return
            for (i in start.sectionsDone until n) {
                yield() // cancellation point, and lets queries on a shared thread run between sections
                val rows = rowsOf(text.section(i))
                val last = i == n - 1
                index.addSection(i, rows, complete = last)
                onProgress(IndexProgress(i + 1, n, last, running = true, failed = false))
            }
            if (start.sectionsDone >= n) { // no sections at all (or a kill between the last section and completion)
                index.markComplete()
                onProgress(IndexProgress(n, n, true, running = true, failed = false))
            }
        }
    }

    /** One row per extractor segment; the control characters used as highlight markers are blanked (same length). */
    private fun rowsOf(x: app.epubreader.core.epub.text.ExtractedText?): List<IndexRow> =
        x?.segments?.map { IndexRow(it.start, x.text.substring(it.start, it.end).replace('\u0001', ' ').replace('\u0002', ' ')) }.orEmpty()
}
