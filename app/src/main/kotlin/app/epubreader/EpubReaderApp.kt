package app.epubreader

import android.app.Application
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import app.epubreader.bench.BenchHooks

class EpubReaderApp : Application(), SingletonImageLoader.Factory {
    val graph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        BenchHooks.install(this)
    }

    /**
     * Library covers (F-021) with a small footprint, so the reader keeps its memory (P-060): software bitmaps in
     * RGB_565 (covers need no alpha), an 8 MB memory cache, no disk cache (the thumbnails are files already).
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .allowHardware(false)
        .bitmapConfig(Bitmap.Config.RGB_565)
        .memoryCache { MemoryCache.Builder().maxSizeBytes(8L * 1024 * 1024).build() }
        .diskCache(null)
        .build()
}
