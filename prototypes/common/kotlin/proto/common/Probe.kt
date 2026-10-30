package proto.common

import android.os.SystemClock
import android.util.Log

/** Logs timestamped events ("EVT <name> t=<uptimeMs> ...") for the measurement drivers. */
object Probe {
    fun ev(name: String, extra: String = "") {
        Log.i("PROTO", "EVT $name t=${SystemClock.uptimeMillis()} $extra".trimEnd())
    }
}
