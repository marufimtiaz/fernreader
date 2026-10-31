package app.epubreader.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Writes reading positions off the UI thread (R-005) and transactionally (R-009). Bursts of page turns collapse to
 * the newest update, so at most the last page change can be lost if the process dies. Timestamps are taken at
 * submit time, so a late write of an older update never replaces a newer row (PositionDao.put).
 */
class PositionWriter(private val db: AppDatabase, private val clock: HybridClock, scope: CoroutineScope) {
    data class Update(val bookId: String, val cfi: String, val fraction: Double, val spineIndex: Int)

    private val pending = Channel<Pair<Update, Long>>(Channel.CONFLATED)

    init {
        scope.launch { for ((u, at) in pending) write(u, at) }
    }

    fun submit(u: Update) {
        pending.trySend(u to clock.now())
    }

    /** Bypasses the queue; used when a book is closed so switching books never drops its last position. */
    suspend fun writeNow(u: Update) = write(u, clock.now())

    private suspend fun write(u: Update, at: Long) {
        db.positionDao().put(u.bookId, u.cfi, u.fraction, u.spineIndex, at)
    }
}
