package app.epubreader

import android.app.Application
import app.epubreader.core.data.Backup
import app.epubreader.core.data.HybridClock
import app.epubreader.core.data.seedClock
import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.PositionWriter
import app.epubreader.core.data.AnnotationStore
import app.epubreader.core.data.BookmarkStore
import app.epubreader.core.data.SearchHistoryStore
import app.epubreader.core.data.SettingsStore
import app.epubreader.core.search.SearchIndexes
import app.epubreader.reader.BookRemoval
import app.epubreader.reader.BookSearch
import app.epubreader.reader.SearchIndexWorker
import android.os.Process
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import app.epubreader.core.library.FileStorageWalker
import app.epubreader.core.library.ScanController
import app.epubreader.core.library.ScanScope
import app.epubreader.core.library.Scanner
import app.epubreader.bench.Probe
import app.epubreader.library.CoverExtractor
import app.epubreader.library.CoverQueue
import app.epubreader.library.StorageAccess
import app.epubreader.settings.AppPrefs
import app.epubreader.settings.AppThemeCache
import android.os.Environment
import app.epubreader.core.data.androidDatabaseBuilder
import app.epubreader.core.data.buildDatabase
import app.epubreader.reader.BookContentServer
import app.epubreader.reader.BookOpener
import app.epubreader.reader.BookSources
import app.epubreader.reader.ChunkDirs
import android.os.storage.StorageManager
import kotlinx.coroutines.CoroutineExceptionHandler
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
     * F-050 indexes. Builds run on one thread at background priority (the plan's "Dispatchers.IO at low priority"), so
     * they never compete with reading for CPU; queries run on Dispatchers.IO at normal priority.
     */
    val search: SearchIndexes by lazy {
        val thread = Executors.newSingleThreadExecutor { r ->
            Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "search-index").apply { isDaemon = true }
        }
        SearchIndexes(File(app.filesDir, "search").path, thread.asCoroutineDispatcher())
    }
    /** Removing a book also drops its search data (F-018 remove, F-025 delete). */
    val removal: BookRemoval by lazy { BookRemoval(library::removeBook, searchHistory) { search.delete(it) } }
    val bookSearch: BookSearch by lazy {
        // WorkManager initialises on first use (on demand, see EpubReaderApp): keep that off the main thread.
        BookSearch(search, opener) { io.launch { SearchIndexWorker.enqueue(app) } }
    }
    /** F-074/F-017 app settings; the theme starts from a small preferences copy so the first frame is right. */
    val prefs = AppPrefs(settings, io, initialTheme = AppThemeCache.read(app))
    val scanner = Scanner(db, clock, FileStorageWalker(Environment.getExternalStorageDirectory().path))
    /** F-003/F-012: scans run on IO, never before the first frame (MainActivity calls onAppStart after it). */
    val scans = ScanController(
        scan = scanner::scan, scope = io, access = { StorageAccess.hasAccess(app) },
        scopeSetting = { settings.get(SettingsStore.LIBRARY_SCOPE)?.takeIf { it != "everywhere" }?.let { ScanScope.Folder(it) } ?: ScanScope.Everywhere },
        probe = { n, x -> Probe.ev(n, x) },
        onComplete = { covers.fillMissing() },
    )
    /** F-090: a discovery folder from a backup is restored only if it exists on this device. */
    val backup = Backup(db, clock, folderExists = { java.io.File(it).isDirectory })
    val covers: CoverQueue by lazy { CoverQueue(library, CoverExtractor(java.io.File(app.filesDir, "thumbs")), io) }
}
