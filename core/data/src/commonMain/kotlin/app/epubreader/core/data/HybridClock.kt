package app.epubreader.core.data

import kotlin.jvm.Synchronized

/** Change timestamps for synced rows (F-115): wall-clock ms, but never ≤ the previous value. */
class HybridClock(private val wall: () -> Long) {
    private var last = 0L

    @Synchronized
    fun now(): Long {
        val w = wall()
        last = if (w > last) w else last + 1
        return last
    }
}
