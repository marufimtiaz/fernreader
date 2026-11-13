package app.epubreader

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/** C-113 (b): work on the background dispatcher runs only on threads that first set background priority. */
class BackgroundThreadsTest {
    @Test fun every_thread_sets_its_priority_before_running_work() = runBlocking {
        val lowered = ConcurrentHashMap.newKeySet<Thread>()
        val d = BackgroundThreads.dispatcher("bg", 2, factory = BackgroundThreads("bg") { lowered += Thread.currentThread() })
        try {
            val ran = (1..20).map { withContext(d) { Thread.currentThread() } }
            assertTrue("work ran on lowered threads only: $ran", ran.all { it in lowered })
            assertTrue(ran.all { it.name.startsWith("bg-") && it.isDaemon })
            assertTrue("at most 2 threads: ${ran.toSet()}", ran.toSet().size <= 2)
        } finally { d.close() }
    }

    @Test fun idle_threads_end() = runBlocking {
        val threads = ConcurrentHashMap.newKeySet<Thread>()
        val d = BackgroundThreads.dispatcher("bg", 1, keepAliveSeconds = 1, factory = BackgroundThreads("bg") { threads += Thread.currentThread() })
        try {
            withContext(d) { }
            val t = threads.single()
            t.join(5_000)
            assertEquals("the idle thread ended", false, t.isAlive)
        } finally { d.close() }
    }
}
