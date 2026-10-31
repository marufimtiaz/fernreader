package app.epubreader.bench

import android.app.Application

/** Benchmark build type only: enable Probe events for the milestone suite. */
object BenchHooks {
    fun install(app: Application) { Probe.enabled = true; Probe.ev("app_create") }
}
