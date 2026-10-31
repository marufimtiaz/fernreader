package app.epubreader.reader

import app.epubreader.reader.bridge.FromWeb
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Events from the web side to the controller. Nothing is ever dropped: the controller's collector may be suspended
 * on a database write while a burst arrives, and the event lost could be an `opened`, a `seeked` or the relocate
 * of the user's last page. The buffer is unbounded (SUSPEND policy, so [emit] never fails); a burst is short, the
 * collector catches up.
 */
class ReaderEvents {
    private val events = MutableSharedFlow<FromWeb>(extraBufferCapacity = Int.MAX_VALUE)
    val flow: SharedFlow<FromWeb> = events

    /** Never suspends; returns false only if the buffer were full, which an unbounded one never is. */
    fun emit(m: FromWeb): Boolean = events.tryEmit(m)
}
