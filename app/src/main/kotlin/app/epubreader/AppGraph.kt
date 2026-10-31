package app.epubreader

import android.app.Application
import app.epubreader.core.data.HybridClock
import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.PositionWriter
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
}
