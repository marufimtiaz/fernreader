package app.epubreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.epubreader.MainActivity
import app.epubreader.core.data.SourceRef
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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

/** Reader events in arrival order for a scenario, with ordered waits (no timing guesses). */
class ReaderHarness(val sc: ActivityScenario<MainActivity>, scope: CoroutineScope) {
    lateinit var a: MainActivity
    private val ch = Channel<FromWeb>(Channel.UNLIMITED)
    private val job: Job
    var at: FromWeb.Relocate? = null
    private var ids = 5_000

    init {
        sc.onActivity { a = it }
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) { a.reader.events.collect { ch.send(it) } }
    }

    suspend fun ready() = withTimeout(15_000) { a.reader.isReady.first { it } }
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
    suspend fun open(f: File): FromWeb.Relocate {
        sc.onActivity { it.openBook(SourceRef.File(f.path)) }
        relocate(15_000); painted()
        withTimeout(10_000) { a.controller.state.first { !it.loading && it.toc.isNotEmpty() } }
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
