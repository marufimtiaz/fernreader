package app.fernreader

import android.app.Application
import app.fernreader.core.data.Backup
import app.fernreader.core.data.HybridClock
import app.fernreader.core.data.seedClock
import app.fernreader.core.data.LibraryRepository
import app.fernreader.core.data.PositionWriter
import app.fernreader.core.data.AnnotationStore
import app.fernreader.core.data.BookmarkStore
import app.fernreader.core.data.SearchHistoryStore
import app.fernreader.core.data.SettingsStore
import app.fernreader.core.search.SearchIndexes
import app.fernreader.reader.BookRemoval
import app.fernreader.reader.BookSearch
import app.fernreader.reader.SearchIndexWorker
import java.io.File
import kotlinx.coroutines.launch
import app.fernreader.core.library.FileStorageWalker
import app.fernreader.core.library.ScanController
import app.fernreader.core.library.ScanScope
import app.fernreader.core.library.Scanner
import app.fernreader.bench.Probe
import app.fernreader.library.CoverExtractor
import app.fernreader.library.CoverQueue
import app.fernreader.library.StorageAccess
import app.fernreader.settings.AppPrefs
import app.fernreader.settings.AppThemeCache
import android.os.Environment
import app.fernreader.core.data.androidDatabaseBuilder
import app.fernreader.core.data.buildDatabase
import app.fernreader.reader.BookContentServer
import app.fernreader.reader.BookOpener
import app.fernreader.reader.BookSources
import app.fernreader.reader.ChunkDirs
import android.os.storage.StorageManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import android.util.Log
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Process-wide objects (manual DI). The database opens lazily on first query, off the main thread. */
class AppGraph(app: Application) {
    /** Last guard: a failure in background work (scan, covers, close-out) is logged, never a process crash (R-002). */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e("EPR", "background work failed", e) })
    /** Outlives activities: work that must finish after onDestroy (the reader's close-out) runs here. */
    val appScope: CoroutineScope get() = io
    /**
     * C-113 (b): work the user is not waiting on (scans, covers, stored page counts), on background-priority threads
     * ([BackgroundThreads]). Four threads: the scanner's four loaders; covers take two of them.
     */
    val background: CoroutineDispatcher = BackgroundThreads.dispatcher("background", 4)
    val db = buildDatabase(androidDatabaseBuilder(app))
    val clock = HybridClock(System::currentTimeMillis)
    /** M8 chunk caches, one cache group per book file and policy (trimmed whole by the system, rebuilt when needed). */
    val chunkDirs = ChunkDirs(File(app.cacheDir, "chunks")) { dir ->
        app.getSystemService(StorageManager::class.java)?.setCacheBehaviorGroup(dir, true)
    }
    // Before the library: its start-up cleanup (sessionCleanup) already drops chunk caches through the opener.
    val server = BookContentServer()
    val opener = BookOpener(BookSources(app), server, chunkStore = chunkDirs::store)
    /** M8: drops these sources' chunk caches; their sessions first, so none rebuilds (recreates) a deleted cache. */
    suspend fun forgetChunks(sourceKeys: Collection<String>) {
        opener.forget(sourceKeys)
        chunkDirs.delete(sourceKeys)
    }
    val library = LibraryRepository(
        db, clock, deleteFile = { java.io.File(it).delete() }, // F-025 "also delete the file"
        // F-025 with files: the thumbnail, the search history and the search index go with the book (M5 BookRemoval).
        onBookFilesDeleted = { id, thumb -> thumb?.let { java.io.File(it).delete() }; io.launch { removal.forgetSearch(id) } },
        // M8: chunk caches go with the book_cache rows (F-025 delete) and with sources the library forgets.
        onCacheDeleted = { _, sourceKeys -> forgetChunks(sourceKeys) },
        grantPersisted = { uri -> app.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri.toString() == uri } },
        beforeRecordOpened = { sessionCleanup.await() },
    )
    /**
     * M3 deferred: open-with sources whose grant could not be kept are dropped at process start. Runs first on IO;
     * [LibraryRepository.recordOpened] waits for it so a cold open-with is never dropped.
     */
    val sessionCleanup: Deferred<Unit> = io.async {
        runCatching { library.dropSessionFiles() }
        runCatching { seedClock(db, clock) } // restored stamps may be ahead of this clock (C-011)
        Unit
    }
    /** Created on IO when first awaited: PositionWriter.create seeds the clock from the stored positions. */
    val positions: Deferred<PositionWriter> = io.async(start = CoroutineStart.LAZY) { PositionWriter.create(db, clock, io) }
    val settings = SettingsStore(db, clock)
    val searchHistory = SearchHistoryStore(db, clock)
    val bookmarks = BookmarkStore(db, clock)
    val annotations = AnnotationStore(db, clock)
    /**
     * F-050 indexes. Builds run on one thread at background priority (the plan's "Dispatchers.IO at low priority", C-113 b), so
     * they never compete with reading for CPU; queries (a search the user started) run on Dispatchers.IO at normal priority.
     */
    val search: SearchIndexes by lazy { SearchIndexes(File(app.filesDir, "search").path, BackgroundThreads.dispatcher("search-index", 1)) }
    /** Removing a book also drops its search data (F-018 remove, F-025 delete). */
    val removal: BookRemoval by lazy { BookRemoval(library::removeBook, searchHistory) { search.delete(it) } }
    val bookSearch: BookSearch by lazy {
        // WorkManager initialises on first use (on demand, see EpubReaderApp): keep that off the main thread.
        BookSearch(search, opener) { io.launch { SearchIndexWorker.enqueue(app) } }
    }
    /** F-074/F-017 app settings; the theme starts from a small preferences copy so the first frame is right. */
    val prefs = AppPrefs(settings, io, initialTheme = AppThemeCache.read(app))
    val scanner = Scanner(db, clock, FileStorageWalker(Environment.getExternalStorageDirectory().path), io = background) // C-113 (b)
    /** F-003/F-012: scans run on IO, never before the first frame (MainActivity calls onAppStart after it). */
    val scans = ScanController(
        scan = scanner::scan, scope = io, access = { StorageAccess.hasAccess(app) },
        scopeSetting = { settings.get(SettingsStore.LIBRARY_SCOPE)?.takeIf { it != "everywhere" }?.let { ScanScope.Folder(it) } ?: ScanScope.Everywhere },
        probe = { n, x -> Probe.ev(n, x) },
        onComplete = { covers.fillMissing() },
        resumeDelayMs = 300, // P-002: off the resumed screen's first frame
    )
    /** F-090: a discovery folder from a backup is restored only if it exists on this device. */
    val backup = Backup(db, clock, folderExists = { java.io.File(it).isDirectory })
    val covers: CoverQueue by lazy { CoverQueue(library, CoverExtractor(java.io.File(app.filesDir, "thumbs")), io, background.limitedParallelism(2)) }
}
