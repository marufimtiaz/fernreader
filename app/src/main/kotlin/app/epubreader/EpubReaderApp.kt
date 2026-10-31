package app.epubreader

import android.app.Application
import app.epubreader.bench.BenchHooks

class EpubReaderApp : Application() {
    val graph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        BenchHooks.install(this)
    }
}
