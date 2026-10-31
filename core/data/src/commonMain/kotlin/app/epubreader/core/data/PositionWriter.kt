package app.epubreader.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Writes reading positions off the UI thread (R-005) and transactionally (R-009). Bursts of page turns collapse to
 * the newest update, so at most the last page change can be lost if the process dies.
 *
 * Every update gets exactly one timestamp, taken at submit/writeNow time, so stamps follow call order and a late
 * write of an older update never replaces a newer row (PositionDao.put). To keep that true when the wall clock is
 * behind stored data (skew, restore), the clock is seeded with the newest stored timestamp BEFORE any stamp is
 * taken: create the writer with [create] (suspend; await it on an IO dispatcher before use). A failed write is
 * logged and skipped; it never stops later positions from being stored.
 */
class PositionWriter private constructor(
    private val db: AppDatabase,
    private val clock: HybridClock,
    scope: CoroutineScope,
) {
    data class Update(val bookId: String, val cfi: String, val fraction: Double, val spineIndex: Int)

    private val pending = Channel<Pair<Update, Long>>(Channel.CONFLATED)

    /** Test hook: runs before each queued write. */
    internal var beforeWrite: (suspend (Update) -> Unit)? = null

    init {
        scope.launch {
            for ((u, at) in pending) {
                try {
                    beforeWrite?.invoke(u)
                    write(u, at)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logWarn("PositionWriter: write for ${u.bookId} failed: $e")
                }
            }
        }
    }

    fun submit(u: Update) {
        pending.trySend(u to clock.now())
    }

    /** Bypasses the queue; used when a book is closed so switching books never drops its last position. */
    suspend fun writeNow(u: Update) = write(u, clock.now())

    private suspend fun write(u: Update, at: Long) {
        db.positionDao().put(u.bookId, u.cfi, u.fraction, u.spineIndex, at)
    }

    companion object {
        /** Seeds [clock] from the stored positions, then returns a ready writer. */
        suspend fun create(db: AppDatabase, clock: HybridClock, scope: CoroutineScope): PositionWriter {
            clock.observe(db.positionDao().maxUpdatedAt() ?: 0L)
            return PositionWriter(db, clock, scope)
        }
    }
}

/** No logging library in commonMain; keep one minimal sink here. */
internal fun logWarn(message: String) = println(message)
