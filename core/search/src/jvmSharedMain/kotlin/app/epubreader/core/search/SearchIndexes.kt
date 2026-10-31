package app.epubreader.core.search

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    /** The file digest each book's [states] entry describes (last built or peeked). Guarded by [lock]. */
    private val digests = HashMap<String, String>()
    /** Books being deleted: [ensureBuilt] ignores them until the files are gone. Guarded by [lock]. */
    private val deleting = HashSet<String>()

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
            if (bookId in deleting) return
            if (builds[bookId]?.isActive == true) return
            val known = state.value
            // Complete for this very file: nothing to do (no job, no file open that would contend with a query).
            if (known.complete && !known.failed && digests[bookId] == digest) return
            fromDisk += bookId
            // The file changed in place: what the old digest completed says nothing about this one.
            state.value = if (digests[bookId] != null && digests[bookId] != digest) IDLE.copy(running = true)
                else state.value.copy(running = true, failed = false)
            digests[bookId] = digest
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
                synchronized(lock) {
                    if (builds[bookId] === job) {
                        builds.remove(bookId)
                        state.value = state.value.copy(running = false)
                    }
                }
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
            synchronized(lock) {
                if (state.compareAndSet(IDLE, IndexProgress(m.sectionsDone, m.sections, m.complete, running = false, failed = false))) {
                    digests[bookId] = m.digest
                }
            }
        }
        var reader: SQLiteConnection? = null
        var after = 0L
        var total = 0
        var limit = QueryRunner.FIRST_PAGE
        try {
            while (true) {
                val seen = state.value // read before querying: later commits change it and are picked up next round
                if (reader == null) reader = openReaderWithRetry(path, digest)
                while (reader != null) {
                    val rows = QueryRunner.page(reader, match, after, limit)
                    val hits = ArrayList<SearchHit>()
                    var capped = false
                    scan@ for (row in rows) {
                        for (h in QueryRunner.hitsOf(row)) {
                            // Capped only when a hit exists beyond the cap: exactly cap hits is a complete result.
                            if (total + hits.size >= query.cap) { capped = true; break@scan }
                            hits += h
                        }
                    }
                    if (rows.isNotEmpty()) after = rows.last().rowid
                    total += hits.size
                    if (capped) { emit(SearchUpdate(hits, total, capped = true, done = true)); return@flow }
                    if (hits.isNotEmpty()) emit(SearchUpdate(hits, total, capped = false, done = false))
                    val requested = limit
                    limit = QueryRunner.PAGE
                    if (rows.size < requested) break
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
        val job = synchronized(lock) { deleting += bookId; builds[bookId] }
        try {
            job?.cancelAndJoinQuietly()
            SearchIndex.deleteFiles(pathOf(bookId))
        } finally {
            synchronized(lock) {
                fromDisk -= bookId
                digests -= bookId
                state(bookId).value = IDLE
                deleting -= bookId
            }
        }
    }

    /** Books whose index file exists but is not complete (for the idle job that finishes them). */
    fun incomplete(): List<String> =
        File(dir).listFiles { f -> f.name.endsWith(".db") }.orEmpty()
            .map { it.name.removeSuffix(".db") }
            .filter { SearchIndex.peek(pathOf(it))?.complete != true }
            .sorted()

    /** Stops all builds. The indexes stay on disk. */
    fun close() = scope.cancel()

    /** A transient failure to read the file (still locked after the busy timeout) must not look like "no index". */
    private suspend fun openReaderWithRetry(path: String, digest: String): SQLiteConnection? {
        repeat(OPEN_ATTEMPTS - 1) {
            try { return SearchIndex.openReader(path, digest) } catch (e: SQLiteException) { delay(OPEN_RETRY_MS) }
        }
        return try { SearchIndex.openReader(path, digest) } catch (e: SQLiteException) { null } // damaged for good
    }

    private fun state(bookId: String) = states.getOrPut(bookId) { MutableStateFlow(IDLE) }

    private fun pathOf(bookId: String): String {
        require(bookId.isNotEmpty() && '/' !in bookId && '\\' !in bookId) { "bad book id" }
        return File(dir, "$bookId.db").path
    }

    private suspend fun Job.cancelAndJoinQuietly() { cancel(); join() }

    private companion object {
        const val OPEN_ATTEMPTS = 5
        const val OPEN_RETRY_MS = 100L
        val IDLE = IndexProgress(0, 0, complete = false, running = false, failed = false)
    }
}
