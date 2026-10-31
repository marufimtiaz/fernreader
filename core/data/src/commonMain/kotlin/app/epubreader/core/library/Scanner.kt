package app.epubreader.core.library

import app.epubreader.core.data.AppDatabase
import app.epubreader.core.data.HybridClock
import app.epubreader.core.data.ScanFailureEntity
import app.epubreader.core.data.ScannedBook
import app.epubreader.core.epub.meta.BookIdentity
import app.epubreader.core.epub.meta.MetadataReader
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.pkg.FailReason
import app.epubreader.core.epub.pkg.LoadResult
import app.epubreader.core.epub.pkg.PackageLoader
import app.epubreader.core.epub.zip.RandomAccessSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.TimeSource

/**
 * Library scan (F-010..F-012, F-015, F-016, R-004, R-010). The walker streams candidate files into a channel and
 * [workers] coroutines load them while the walk continues (M0 S3). Unchanged files (same path, size and mtime) cost
 * no zip I/O. Rows of files that were not seen are pruned only after a walk that completed: a cancelled or killed
 * scan never removes anything (Review Focus 3). Reading data is keyed by book id and never touched here.
 */
@OptIn(ExperimentalAtomicApi::class)
class Scanner(
    private val db: AppDatabase,
    private val clock: HybridClock,
    private val walker: StorageWalker,
    private val open: (String) -> RandomAccessSource = ::openFileSource,
    private val workers: Int = 4,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()

    /** [onEvent] is called from worker coroutines (several threads): it must be thread-safe. */
    suspend fun scan(scope: ScanScope, onEvent: (ScanEvent) -> Unit = {}): ScanEvent.Complete = lock.withLock {
        withContext(io) {
            val start = TimeSource.Monotonic.markNow()
            val dao = db.libraryDao()
            val scanId = clock.now()
            val known = dao.scanFiles().associateBy { it.path }
            val knownFailures = dao.failureRows().associateBy { it.path }
            onEvent(ScanEvent.Started(full = known.isEmpty()))
            val unchanged = ArrayList<String>()
            val unchangedFailures = ArrayList<String>()
            val seenLock = Mutex()
            val files = AtomicInt(0); val added = AtomicInt(0); val failed = AtomicInt(0)

            suspend fun process(f: FoundFile) {
                files.incrementAndFetch()
                val row = known[f.path]
                if (row != null && row.size == f.size && row.mtimeMs == f.mtimeMs) { seenLock.withLock { unchanged += f.path }; return }
                val fail = knownFailures[f.path]
                if (fail != null && fail.size == f.size && fail.mtimeMs == f.mtimeMs) {
                    failed.incrementAndFetch(); seenLock.withLock { unchangedFailures += f.path }; return
                }
                when (val r = load(f)) {
                    is Loaded.Book -> {
                        val id = dao.recordScanned(r.book, scanId, clock.now())
                        added.incrementAndFetch()
                        onEvent(ScanEvent.BookAdded(id, f.path))
                    }
                    is Loaded.Failure -> {
                        dao.upsertFailure(ScanFailureEntity(f.path, f.folder, f.path.substringAfterLast('/'), r.reason.name, f.size, f.mtimeMs, scanId))
                        failed.incrementAndFetch()
                        onEvent(ScanEvent.Failed(f.path, r.reason))
                    }
                    Loaded.Unreadable -> Unit // gone or not readable right now: not seen, a later scan retries
                }
            }

            coroutineScope {
                val ch = Channel<FoundFile>(256)
                launch { try { walker.walk(scope) { ch.send(it) } } finally { ch.close() } }
                repeat(workers) { launch { for (f in ch) process(f) } }
            }
            // The walk completed (a cancellation throws out of coroutineScope above): now prune what was not seen.
            dao.markSeen(unchanged, unchangedFailures, scanId)
            val prefix = when (scope) { ScanScope.Everywhere -> walker.root; is ScanScope.Folder -> scope.path }.trimEnd('/') + "/"
            val removed = dao.pruneScan(scanId, prefix)
            dao.pruneFailures(scanId, prefix)
            dao.tombstoneOrphans(clock.now())
            ScanEvent.Complete(added.load(), removed, failed.load(), files.load(), start.elapsedNow().inWholeMilliseconds)
                .also(onEvent)
        }
    }

    private sealed interface Loaded {
        class Book(val book: ScannedBook) : Loaded
        class Failure(val reason: FailReason) : Loaded
        data object Unreadable : Loaded
    }

    private fun load(f: FoundFile): Loaded {
        val src = try { open(f.path) } catch (e: Exception) { return Loaded.Unreadable }
        return try {
            src.use {
                val (zip, r) = PackageLoader.open(it)
                when (r) {
                    is LoadResult.Failed -> Loaded.Failure(r.reason)
                    is LoadResult.Ok -> {
                        val m = r.pkg.metadata
                        val title = m.title ?: f.path.substringAfterLast('/').substringBeforeLast('.') // Review Focus 5
                        val authors = m.authors.joinToString("\n")
                        Loaded.Book(
                            ScannedBook(
                                identityKey = BookIdentity.of(r.pkg.uniqueId, zip!!.centralDirectoryDigest),
                                title = title, sortTitle = MetadataReader.sortKey(title), authors = authors,
                                sortAuthor = m.authors.firstOrNull()?.lowercase() ?: "", language = m.language,
                                titleDirection = if (MetadataReader.isRtl(title)) "rtl" else "auto",
                                path = f.path, folder = f.folder, size = f.size, mtimeMs = f.mtimeMs,
                            ),
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { // an I/O failure while reading (file shrank, removed mid-read): retry next scan
            Loaded.Unreadable
        } catch (e: OutOfMemoryError) { // hostile content beyond the parser's bounds: report it, never retry or crash
            Loaded.Failure(FailReason.BROKEN_PACKAGE)
        } catch (e: StackOverflowError) {
            Loaded.Failure(FailReason.BROKEN_PACKAGE)
        }
    }
}
