package app.epubreader.reader

import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.PositionMap
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
    /**
     * [map]: the opened book's virtual/original position map (M8: its ChunkMap when a huge document was split, else
     * the identity). Every position between the reader and storage goes through it (plan: Global Constraints).
     */
    data class Ok(val sessionId: String, val cdDigest: String, val size: Long, val map: PositionMap = PositionMap.Identity) : OpenResult
    data class Failed(val reason: OpenFailure) : OpenResult
}

/** Opens a book's bytes for the reader (implemented by app's BookOpener). Never called on the main thread's time. */
interface BookOpenerPort {
    suspend fun open(ref: SourceRef): OpenResult
    suspend fun close(sessionId: String)
    /**
     * Text size of every section the reader shows, in reading order (page estimates, F-034): the spine items, or for a
     * chunked book (M8) its virtual sections, parts included. Null if the package can't be read.
     */
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

/**
 * Hands an external link to another app (implemented in app with an ACTION_VIEW intent). Returns false when no app
 * can open it. Main thread; must not block.
 */
fun interface ExternalLinkOpener {
    fun open(url: String): Boolean
}

/**
 * F-060 selection menu: Copy and Share (implemented in app: the clipboard as plain text, an ACTION_SEND chooser).
 * Main thread; must not block.
 */
interface TextActions {
    fun copy(text: String)
    fun share(text: String)
}

/** [chars]: canonical text length with whitespace runs as one; [images]: img/image/svg elements. */
@kotlinx.serialization.Serializable
data class SectionStat(val chars: Int, val images: Int)

/** Benchmark events (app's Probe); a no-op in normal builds. */
fun interface EventProbe {
    fun ev(name: String, extra: String)

    companion object { val None = EventProbe { _, _ -> } }
}
