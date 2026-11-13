package app.epubreader

import android.os.Process
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * C-113 (b): threads for work the user is not waiting on (library scans, cover extraction and encoding, the search
 * index build, the stored screen-page counts). Each thread sets its own priority to [Process.THREAD_PRIORITY_BACKGROUND]
 * before it runs anything; at that priority Android also moves it to the background scheduling group, which the
 * system keeps on its power-saving cores. Work the user waits for (opening a book, a page turn, a search the user
 * started, the Contents panel) stays on Dispatchers.IO / Default at normal priority.
 *
 * [setPriority] runs on the new thread (Android: the line above; tests record it).
 */
class BackgroundThreads(
    private val name: String,
    private val setPriority: () -> Unit = { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) },
) : ThreadFactory {
    private val n = AtomicInteger()

    override fun newThread(r: Runnable): Thread =
        Thread({ setPriority(); r.run() }, "$name-${n.incrementAndGet()}").apply { isDaemon = true }

    companion object {
        /**
         * Up to [threads] background-priority threads. Idle threads end after [keepAliveSeconds], so nothing is
         * parked while no such work runs.
         */
        fun dispatcher(name: String, threads: Int, keepAliveSeconds: Long = 10, factory: ThreadFactory = BackgroundThreads(name)): ExecutorCoroutineDispatcher =
            ThreadPoolExecutor(threads, threads, keepAliveSeconds, TimeUnit.SECONDS, LinkedBlockingQueue(), factory)
                .apply { allowCoreThreadTimeOut(true) }
                .asCoroutineDispatcher()
    }
}
