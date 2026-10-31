package app.epubreader

import android.app.Application
import app.epubreader.bench.BenchHooks

class EpubReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BenchHooks.install(this)
    }
}
