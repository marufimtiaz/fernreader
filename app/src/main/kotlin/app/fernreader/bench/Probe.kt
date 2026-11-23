package app.fernreader.bench

import android.os.SystemClock
import android.util.Log

/**
 * Timestamped events for the benchmark harness ("EVT <name> t=<uptimeMs> ..." on logcat tag EPR).
 * Enabled only in the `benchmark` build type (BenchHooks); a no-op elsewhere.
 */
object Probe {
    @Volatile var enabled = false
    fun ev(name: String, extra: String = "") {
        if (enabled) Log.i("EPR", "EVT $name t=${SystemClock.uptimeMillis()} $extra".trimEnd())
    }
}
