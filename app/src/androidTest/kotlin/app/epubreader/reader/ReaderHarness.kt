package app.epubreader.reader

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.epubreader.EpubReaderApp
import app.epubreader.MainActivity
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.SourceRef
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File

/** War and Peace from the pushed corpus (bench/push_corpus.sh); all-files access is allowed before the activity starts. */
fun warAndPeace(): File {
    val inst = InstrumentationRegistry.getInstrumentation()
    inst.uiAutomation.executeShellCommand("appops set ${inst.targetContext.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
    return File("/sdcard/Books/gutenberg/war-and-peace-epub3.epub").also { check(it.isFile) { "corpus not pushed: $it" } }
}

private suspend fun MainActivity.storedSettings() = ReaderSettings.decode((application as EpubReaderApp).graph.settings.get(ReaderSettings.KEY))

/**
 * The settings in force and the stored ones agree: the controller's load after a launch (which would replace a change
 * made before it) has run or would change nothing, and a change (stored 300 ms after it) has been written, so the next
 * launch reads it.
 */
suspend fun MainActivity.settingsStored() = withTimeout(10_000) { while (storedSettings() != controller.settings.value) delay(20) }

/** Reader events in arrival order for a scenario, with ordered waits (no timing guesses). */
class ReaderHarness(val sc: ActivityScenario<MainActivity>, scope: CoroutineScope) {
    lateinit var a: MainActivity
    private val ch = Channel<FromWeb>(Channel.UNLIMITED)
    private val job: Job
    var at: FromWeb.Relocate? = null
    private var ids = 5_000
    @Volatile private var lastEvent = 0L

    init {
        sc.onActivity { a = it }
        // Its own Job: a child of the test's runBlocking would hang the whole suite if a failure skips stop().
        job = scope.launch(Job(), start = CoroutineStart.UNDISPATCHED) { a.reader.events.collect { if (it !is FromWeb.PageCounts) lastEvent = SystemClock.uptimeMillis(); ch.send(it) } }
    }

    suspend fun ready() { withTimeout(15_000) { a.reader.isReady.first { it } }; a.settingsStored() }
    fun send(m: ToWeb) = sc.onActivity { it.reader.send(m) }
    fun stop() = job.cancel()

    private fun see(e: FromWeb) { if (e is FromWeb.Relocate) at = e }
    fun drain() { while (true) see(ch.tryReceive().getOrNull() ?: return) }
    suspend fun <T : FromWeb> next(ms: Long = 10_000, accept: (FromWeb) -> T?): T = withTimeout(ms) {
        while (true) { val e = ch.receive(); see(e); accept(e)?.let { return@withTimeout it } }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    suspend fun relocate(ms: Long = 10_000, ok: (FromWeb.Relocate) -> Boolean = { true }) = next(ms) { e -> (e as? FromWeb.Relocate)?.takeIf(ok) }
    suspend fun painted(ms: Long = 10_000) = next(ms) { it as? FromWeb.Painted }
    /** Waits until everything sent before has been applied: a jump to the current location, answered by `seeked`. */
    suspend fun settle(): FromWeb.Relocate {
        val id = ++ids
        send(ToWeb.GoTo(target = at!!.cfi, id = id))
        next(10_000) { e -> (e as? FromWeb.Seeked)?.takeIf { it.id == id } }
        return at!!
    }
    /**
     * Waits until the reader stands still: for [windowMs] no event came (the background page count's aside) and the
     * layout (scroll position, page, each slot's place and load state) did not change. Fails after [timeoutMs]. 400 ms
     * is more than the 250 ms after which a scroll reports its `relocate` and the 200 ms a jump holds its flags. The reads
     * run on the page's main thread, so a busy page delays them instead of passing for still.
     */
    suspend fun still(windowMs: Long = 400, timeoutMs: Long = 15_000) = withTimeout(timeoutMs) {
        var seen: String? = null
        var since = 0L
        while (true) {
            val now = try { sc.js("JSON.stringify([window.__readerTest.slots(), window.__readerTest.page()])") } catch (e: TimeoutCancellationException) { null }
            if (now == null || now != seen || lastEvent > since) { seen = now; since = SystemClock.uptimeMillis() }
            else if (SystemClock.uptimeMillis() - since >= windowMs) break
            delay(50)
        }
    }
    /** Everything under way has ended and been applied, its events taken in. */
    suspend fun quiet(): FromWeb.Relocate { still(); drain(); return settle() }
    /** Opens [f] and waits until its first page settled ([withToc]: and its contents list arrived). */
    suspend fun open(f: File, withToc: Boolean = true): FromWeb.Relocate {
        sc.onActivity { it.openBook(SourceRef.File(f.path)) }
        relocate(15_000); painted()
        withTimeout(10_000) { a.controller.state.first { !it.loading && (!withToc || it.toc.isNotEmpty()) } }
        return settle()
    }
    suspend fun tocJump(label: String): FromWeb.Relocate {
        val item = a.controller.state.value.toc.first { it.label.contains(label, ignoreCase = true) }
        val id = ++ids
        send(ToWeb.GoTo(target = item.href, id = id))
        next(10_000) { e -> (e as? FromWeb.Seeked)?.takeIf { it.id == id } }
        return at!!
    }
    /** Moves to the last page of the current section (test hook) and returns that location. */
    suspend fun lastPage(): FromWeb.Relocate {
        val from = at!!
        sc.js("window.__readerTest.lastPage()")
        return relocate(5_000) { it.sectionIndex == from.sectionIndex && it.cfi != from.cfi }
    }
}
