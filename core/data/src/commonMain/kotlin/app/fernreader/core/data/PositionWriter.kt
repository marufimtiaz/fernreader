package app.fernreader.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Writes reading positions off the UI thread (R-005) and transactionally (R-009). C-108 (e): at most one write every
 * [minIntervalMs] while reading (the first change after a pause at once, then the newest after the wait; changes in
 * between collapse), and [flush] writes the waiting one at once when the reader is left, the app goes to the background
 * or the book closes ([writeNow]). So nothing a user can see is lost; only a process killed in the foreground can lose
 * the last few seconds of turns.
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
    private val minIntervalMs: Long,
) {
    data class Update(val bookId: String, val cfi: String, val fraction: Double, val spineIndex: Int)

    private val pending = Channel<Pair<Update, Long>>(Channel.CONFLATED)

    /** Test hook: runs before each queued write. */
    internal var beforeWrite: (suspend (Update) -> Unit)? = null

    init {
        scope.launch {
            for ((u, at) in pending) {
                store(u, at)
                delay(minIntervalMs) // only after a write: no timer runs while nothing changes
            }
        }
    }

    fun submit(u: Update) {
        pending.trySend(u to clock.now())
    }

    /** Writes the waiting update now, if there is one (the reader is left, the app goes to the background). */
    suspend fun flush() {
        val (u, at) = pending.tryReceive().getOrNull() ?: return
        store(u, at)
    }

    /** A queued update; a failure is logged and skipped. */
    private suspend fun store(u: Update, at: Long) {
        try {
            beforeWrite?.invoke(u)
            write(u, at)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarn("PositionWriter: write for ${u.bookId} failed: $e")
        }
    }

    /**
     * Writes [u] now, bypassing the wait; used when a book is closed so switching books never drops its last position.
     * A waiting update goes first (it may be another book's).
     */
    suspend fun writeNow(u: Update) {
        val stamp = clock.now()
        pending.tryReceive().getOrNull()?.let { (w, at) -> if (w != u) store(w, at) }
        write(u, stamp)
    }

    private suspend fun write(u: Update, at: Long) {
        db.positionDao().put(u.bookId, u.cfi, u.fraction, u.spineIndex, at)
    }

    companion object {
        /** Seeds [clock] from the stored positions, then returns a ready writer. */
        suspend fun create(db: AppDatabase, clock: HybridClock, scope: CoroutineScope, minIntervalMs: Long = 5_000): PositionWriter {
            clock.observe(db.positionDao().maxUpdatedAt() ?: 0L)
            return PositionWriter(db, clock, scope, minIntervalMs)
        }
    }
}

/** No logging library in commonMain; keep one minimal sink here. */
internal fun logWarn(message: String) = println(message)
