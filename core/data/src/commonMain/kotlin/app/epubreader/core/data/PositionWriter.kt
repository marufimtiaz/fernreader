package app.epubreader.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Writes reading positions off the UI thread (R-005) and transactionally (R-009). Bursts of page turns collapse to
 * the newest update, so at most the last page change can be lost if the process dies. Timestamps are taken at
 * submit time, so a late write of an older update never replaces a newer row (PositionDao.put).
 * The clock is seeded with the newest stored timestamp before the first write, so a wall clock that is behind
 * stored data (skew, restore) cannot make every write look older. A failed write is logged and skipped; it never
 * stops later positions from being stored.
 */
class PositionWriter(private val db: AppDatabase, private val clock: HybridClock, scope: CoroutineScope) {
    data class Update(val bookId: String, val cfi: String, val fraction: Double, val spineIndex: Int)

    private val pending = Channel<Pair<Update, Long>>(Channel.CONFLATED)
    private val seedLock = Mutex()
    private var seededMax: Long? = null

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
                    println("PositionWriter: write for ${u.bookId} failed: $e")
                }
            }
        }
    }

    fun submit(u: Update) {
        pending.trySend(u to clock.now())
    }

    /** Bypasses the queue; used when a book is closed so switching books never drops its last position. */
    suspend fun writeNow(u: Update) {
        seed()
        write(u, clock.now())
    }

    private suspend fun seed(): Long = seedLock.withLock {
        seededMax ?: (db.positionDao().maxUpdatedAt() ?: 0L).also { clock.observe(it); seededMax = it }
    }

    private suspend fun write(u: Update, stamp: Long) {
        val max = seed()
        // A stamp taken before seeding may be older than stored data; restamp (writes are sequential, so order holds).
        val at = if (stamp <= max) clock.now() else stamp
        db.positionDao().put(u.bookId, u.cfi, u.fraction, u.spineIndex, at)
    }
}
