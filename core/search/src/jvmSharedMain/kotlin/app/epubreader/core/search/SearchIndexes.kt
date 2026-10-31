package app.epubreader.core.search

import androidx.sqlite.SQLiteConnection
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * All books' search indexes under [dir] (`<bookId>.db`): builds them in the background on [io] and streams queries on [queries].
 * Pass a low-priority single-thread dispatcher as [io] so a build never competes with reading; queries run on [queries] and
 * interleave with the build between its sections.
 */
class SearchIndexes(
    private val dir: String,
    private val io: CoroutineDispatcher,
    /** Where queries run: normal priority, so a search never waits behind (or at the priority of) a section being indexed. */
    private val queries: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val lock = Any()
    private val builds = HashMap<String, Job>()
    private val states = ConcurrentHashMap<String, MutableStateFlow<IndexProgress>>()
    private val fromDisk: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Build state of [bookId]'s index. Starts as "nothing" and is filled in by [ensureBuilt] or the first [search]
     * (which read the file), so observing it never touches the disk.
     */
    fun progress(bookId: String): StateFlow<IndexProgress> = state(bookId)

    /**
     * Makes sure [bookId]'s index is complete for the book file with [digest]: builds or resumes it in the background.
     * Idempotent: does nothing while a build for this book runs. [text] runs on [io] when the build starts; null = book unavailable.
     */
    fun ensureBuilt(bookId: String, digest: String, text: () -> BookText?) {
        val path = pathOf(bookId)
        val state = state(bookId)
        synchronized(lock) {
            if (builds[bookId]?.isActive == true) return
            fromDisk += bookId
            state.value = state.value.copy(running = true, failed = false)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val book = text() ?: throw BookUnavailableException("no book")
                    IndexBuilder.build(path, digest, book) { state.value = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // A book closed under us, a full disk, a damaged file, out of memory: what is committed stays and the
                    // next ensureBuilt resumes. Never crash the process from a background build.
                    if (e !is BookUnavailableException) println("search index build failed for $bookId: $e")
                    state.value = state.value.copy(failed = true)
                }
            }
            builds[bookId] = job
            // Whatever ended the job (also a scope cancelled before it started), nobody may wait for it any more.
            job.invokeOnCompletion {
                state.value = state.value.copy(running = false)
                synchronized(lock) { if (builds[bookId] === job) builds.remove(bookId) }
            }
            job.start()
        }
    }

    /**
     * Streams hits in book order, in batches, until the end or [SearchQuery.cap]. If the index is still building, waits for
     * its progress and continues (one code path for "built" and "building"). Starts no build itself: call [ensureBuilt]
     * first; an incomplete index that nothing is building ends the search with what it has. A final `done` update is always sent.
     */
    fun search(bookId: String, digest: String, query: SearchQuery): Flow<SearchUpdate> = flow {
        val match = QueryBuilder.build(query)
        if (match == null || query.cap <= 0) { emit(SearchUpdate(emptyList(), 0, false, true)); return@flow }
        val path = pathOf(bookId)
        val state = state(bookId)
        if (fromDisk.add(bookId)) SearchIndex.peek(path)?.let { m ->
            state.compareAndSet(IDLE, IndexProgress(m.sectionsDone, m.sections, m.complete, running = false, failed = false))
        }
        var reader: SQLiteConnection? = null
        var after = 0L
        var total = 0
        try {
            while (true) {
                val seen = state.value // read before querying: later commits change it and are picked up next round
                if (reader == null) reader = SearchIndex.openReader(path, digest)
                while (reader != null) {
                    val rows = QueryRunner.page(reader, match, after)
                    val hits = ArrayList<SearchHit>()
                    var capped = false
                    scan@ for (row in rows) {
                        for (h in QueryRunner.hitsOf(row)) {
                            hits += h
                            if (total + hits.size >= query.cap) { capped = true; break@scan }
                        }
                    }
                    if (rows.isNotEmpty()) after = rows.last().rowid
                    total += hits.size
                    if (capped) { emit(SearchUpdate(hits, total, capped = true, done = true)); return@flow }
                    if (hits.isNotEmpty()) emit(SearchUpdate(hits, total, capped = false, done = false))
                    if (rows.size < QueryRunner.PAGE) break
                }
                if (seen.complete || !seen.running) break
                state.first { it != seen }
            }
            emit(SearchUpdate(emptyList(), total, false, true))
        } finally {
            reader?.close()
        }
    }.flowOn(queries)

    /** Cancels a running build of [bookId] and removes its index. */
    suspend fun delete(bookId: String) {
        val job = synchronized(lock) { builds[bookId] }
        job?.cancelAndJoinQuietly()
        SearchIndex.deleteFiles(pathOf(bookId))
        fromDisk -= bookId
        state(bookId).value = IDLE
    }

    /** Books whose index file exists but is not complete (for the idle job that finishes them). */
    fun incomplete(): List<String> =
        File(dir).listFiles { f -> f.name.endsWith(".db") }.orEmpty()
            .map { it.name.removeSuffix(".db") }
            .filter { SearchIndex.peek(pathOf(it))?.complete != true }
            .sorted()

    /** Stops all builds. The indexes stay on disk. */
    fun close() = scope.cancel()

    private fun state(bookId: String) = states.getOrPut(bookId) { MutableStateFlow(IDLE) }

    private fun pathOf(bookId: String): String {
        require(bookId.isNotEmpty() && '/' !in bookId && '\\' !in bookId) { "bad book id" }
        return File(dir, "$bookId.db").path
    }

    private suspend fun Job.cancelAndJoinQuietly() { cancel(); join() }

    private companion object {
        val IDLE = IndexProgress(0, 0, complete = false, running = false, failed = false)
    }
}
