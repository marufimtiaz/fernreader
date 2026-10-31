package app.epubreader.reader

import app.epubreader.core.data.SourceRef
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The reader WebView as seen by the UI layer (implemented by app's ReaderEngine). */
interface ReaderPort {
    val events: Flow<FromWeb>
    val isReady: StateFlow<Boolean>
    fun send(m: ToWeb)
}

enum class OpenFailure { NO_ACCESS, NOT_A_ZIP, DAMAGED }

sealed interface OpenResult {
    data class Ok(val sessionId: String, val cdDigest: String, val size: Long) : OpenResult
    data class Failed(val reason: OpenFailure) : OpenResult
}

/** Opens a book's bytes for the reader (implemented by app's BookOpener). Never called on the main thread's time. */
interface BookOpenerPort {
    suspend fun open(ref: SourceRef): OpenResult
    suspend fun close(sessionId: String)
    /** Text size of every spine item, in spine order (page estimates, F-034); null if the package can't be read. */
    suspend fun sectionStats(sessionId: String): List<SectionStat>? = null
}

/** In-book search as the UI sees it (implemented in app over core/search's SearchIndexes). */
interface SearchPort {
    /** Starts (or resumes) building this book's index in the background; idempotent. Must not block. */
    fun ensureIndex(bookId: String, digest: String, sessionId: String)
    /** The book was closed: if its index is unfinished, arrange for it to be finished later. Must not block. */
    fun bookClosed(bookId: String) {}
    /** Hits in book order, in batches; the last update has `done = true`. Cancelling the collector stops the search. */
    fun search(bookId: String, digest: String, sessionId: String, query: app.epubreader.core.search.SearchQuery): Flow<app.epubreader.core.search.SearchUpdate>
}

/** [chars]: canonical text length with whitespace runs as one; [images]: img/image/svg elements. */
@kotlinx.serialization.Serializable
data class SectionStat(val chars: Int, val images: Int)

/** Benchmark events (app's Probe); a no-op in normal builds. */
fun interface EventProbe {
    fun ev(name: String, extra: String)

    companion object { val None = EventProbe { _, _ -> } }
}
