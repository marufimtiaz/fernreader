package app.epubreader

import android.app.Application
import app.epubreader.core.data.HybridClock
import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.PositionWriter
import app.epubreader.core.data.SettingsStore
import app.epubreader.core.library.FileStorageWalker
import app.epubreader.core.library.ScanController
import app.epubreader.core.library.ScanScope
import app.epubreader.core.library.Scanner
import app.epubreader.bench.Probe
import app.epubreader.library.StorageAccess
import android.os.Environment
import app.epubreader.core.data.androidDatabaseBuilder
import app.epubreader.core.data.buildDatabase
import app.epubreader.reader.BookContentServer
import app.epubreader.reader.BookOpener
import app.epubreader.reader.BookSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Process-wide objects (manual DI). The database opens lazily on first query, off the main thread. */
class AppGraph(app: Application) {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Outlives activities: work that must finish after onDestroy (the reader's close-out) runs here. */
    val appScope: CoroutineScope get() = io
    val db = buildDatabase(androidDatabaseBuilder(app))
    val clock = HybridClock(System::currentTimeMillis)
    val library = LibraryRepository(db, clock)
    /** Created on IO when first awaited: PositionWriter.create seeds the clock from the stored positions. */
    val positions: Deferred<PositionWriter> = io.async(start = CoroutineStart.LAZY) { PositionWriter.create(db, clock, io) }
    val server = BookContentServer()
    val opener = BookOpener(BookSources(app), server)
    val settings = SettingsStore(db, clock)
    val scanner = Scanner(db, clock, FileStorageWalker(Environment.getExternalStorageDirectory().path))
    /** F-003/F-012: scans run on IO, never before the first frame (MainActivity calls onAppStart after it). */
    val scans = ScanController(
        scan = scanner::scan, scope = io, access = { StorageAccess.hasAccess(app) },
        scopeSetting = { settings.get(SettingsStore.LIBRARY_SCOPE)?.takeIf { it != "everywhere" }?.let { ScanScope.Folder(it) } ?: ScanScope.Everywhere },
        probe = { n, x -> Probe.ev(n, x) },
    )
}
