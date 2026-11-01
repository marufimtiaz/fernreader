package app.epubreader.robustness

import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.epubreader.EpubReaderApp
import app.epubreader.MainActivity
import app.epubreader.core.epub.pkg.FailReason
import app.epubreader.core.library.ScanEvent
import app.epubreader.core.library.ScanScope
import app.epubreader.reader.Notice
import app.epubreader.reader.OpenFailure
import app.epubreader.reader.ReaderHarness
import app.epubreader.reader.hook
import app.epubreader.reader.js
import app.epubreader.ui.Copy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * R-001..R-004 and C-048 on the corpus's malformed books (bench/push_corpus.sh): damaged books still open; unreadable
 * files are reported by the scan (which completes) and give a clear message via open-with; no crash, Back returns.
 */
@RunWith(AndroidJUnit4::class)
class MalformedBooksTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val graph get() = (ctx.applicationContext as EpubReaderApp).graph
    private val generated = File("/sdcard/Books/generated")
    private val dir = File(Environment.getExternalStorageDirectory(), "Download/r-malformed")

    private fun corpus(name: String) = (if (name == "fake.epub") File("/sdcard/Download/fake.epub") else File(generated, name))
        .also { check(it.isFile) { "corpus not pushed: $it" } }

    @Before fun access() {
        inst.uiAutomation.executeShellCommand("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
    }

    @After fun cleanUp(): Unit = runBlocking {
        if (dir.exists()) { dir.deleteRecursively(); graph.scanner.scan(ScanScope.Folder(dir.parent!!)) } // prune its rows
    }

    /** R-002, R-003, R-004 via scan: each unreadable file is reported with its reason, nothing else stops the scan. */
    @Test fun scan_reports_every_unreadable_file_and_adds_the_readable_ones(): Unit = runBlocking {
        val expected = mapOf(
            "malformed-not-a-zip.epub" to FailReason.NOT_A_ZIP,
            "malformed-truncated.epub" to FailReason.DAMAGED_ARCHIVE,
            "malformed-broken-opf.epub" to FailReason.BROKEN_PACKAGE,
            "malformed-no-container.epub" to FailReason.NO_PACKAGE,
            "malformed-empty-spine.epub" to FailReason.NOTHING_READABLE,
            "fake.epub" to FailReason.NOT_A_ZIP,
        )
        val readable = listOf("malformed-missing-files-bad-xml.epub", "no-cover.epub")
        dir.deleteRecursively(); dir.mkdirs()
        // fake.epub first in name order is irrelevant: the walk order is the file system's; every file must be seen.
        for (n in expected.keys + readable) corpus(n).copyTo(File(dir, n))
        val failed = mutableMapOf<String, FailReason>()
        val done = graph.scanner.scan(ScanScope.Folder(dir.path)) { e -> if (e is ScanEvent.Failed) failed[File(e.path).name] = e.reason }
        assertEquals(expected, failed)
        assertEquals(expected.size, done.failed)
        assertEquals("every file was seen", expected.size + readable.size, done.files)
        // The report (F-016) lists them; the library has only the readable ones.
        val report = graph.library.failures().first().filter { it.folder == dir.path }.associate { it.name to it.reason }
        assertEquals(expected.mapValues { it.value.name }, report)
        // The readable copies are known books (the same files are in Books/generated): their paths join those books (C-009).
        val inLibrary = graph.library.library().first().flatMap { it.paths + it.path }.map(::File).filter { it.parent == dir.path }.map { it.name }.toSet()
        assertEquals(readable.toSet(), inLibrary)
    }

    /** R-002/R-003/C-048 via open-with: a clear message (no raw script error), the Back button returns to the caller. */
    @Test fun open_with_each_unreadable_file_shows_a_clear_message_and_returns(): Unit = runBlocking {
        val cases = listOf(
            "malformed-not-a-zip.epub" to listOf(Copy.openFailure(OpenFailure.NOT_A_ZIP)),
            "malformed-truncated.epub" to listOf(Copy.openFailure(OpenFailure.DAMAGED)),
            "fake.epub" to listOf(Copy.openFailure(OpenFailure.NOT_A_ZIP)),
            "malformed-broken-opf.epub" to listOf(Copy.engineError),
            "malformed-no-container.epub" to listOf(Copy.engineError, "No package found in this file."),
            "malformed-empty-spine.epub" to listOf(Copy.engineError, "This book has no readable sections."),
        )
        val empty = "empty-0-bytes.epub" to listOf(Copy.openFailure(OpenFailure.NOT_A_ZIP)) // a failed download
        for ((name, texts) in cases + empty) {
            val f = File(ctx.cacheDir, "testbooks").apply { mkdirs() }.resolve("r-$name")
            if (name == empty.first) f.writeBytes(ByteArray(0)) else corpus(name).copyTo(f, overwrite = true)
            val uri = Uri.parse("content://app.fernreader.test.books/${f.name}")
            val view = Intent(Intent.ACTION_VIEW, uri, ctx, MainActivity::class.java).apply { setDataAndType(uri, "application/epub+zip") }
            ActivityScenario.launch<MainActivity>(view).use { sc ->
                lateinit var a: MainActivity
                sc.onActivity { a = it }
                withTimeout(20_000) { a.controller.state.first { it.error != null } }
                for (t in texts) assertNotNull("$name: '$t' not shown", device.wait(Until.findObject(By.text(t)), 5_000))
                assertTrue("$name: raw script error shown", !device.hasObject(By.textContains("Cannot destructure")) && !device.hasObject(By.textContains("Failed to load")))
                device.findObject(By.text(Copy.back)).click()
                withTimeout(5_000) { while (sc.state != Lifecycle.State.DESTROYED) delay(20) }
            }
        }
    }

    /** R-001: the damaged book opens; its good chapters and the bad-XML chapter show text; a TOC entry to the missing file gives a notice and the page stays. */
    @Test fun the_partly_damaged_book_opens_and_reads(): Unit = runBlocking {
        val book = corpus("malformed-missing-files-bad-xml.epub")
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            h.ready()
            val first = h.open(book)
            assertTrue(sc.hook("page()").toString(), sc.hook("page()").getInt("textPages") >= 1)
            val bad = h.tocJump("Bad XML")
            assertTrue("bad chapter text", sc.firstBlock(bad.sectionIndex).contains("Bad XML Chapter"))
            val c = h.a.controller
            val missing = c.state.value.toc.first { it.label == "Missing" }
            sc.onActivity { c.onTocSelected(missing) }
            withTimeout(3_000) { c.state.first { it.notice == Notice.MissingTarget } }
            h.drain()
            assertEquals("stays on the page", bad.cfi, h.settle().cfi)
            // good2.xhtml#nope (a missing anchor): staying on the page or landing at the chapter start are both harmless
            val anchor = h.tocJump("Missing anchor")
            val last = org.json.JSONArray(org.json.JSONTokener(sc.js("JSON.stringify(window.__readerTest.linear())")).nextValue() as String).let { it.getInt(it.length() - 1) }
            assertTrue("missing anchor: $anchor", anchor.cfi == bad.cfi || anchor.sectionIndex == last)
            h.drain()
            sc.js("window.__readerTest.goIndex($last)") // the second good chapter, the spine's last item
            h.relocate(5_000) { it.sectionIndex == last }
            assertTrue("good2 text", sc.firstBlock(last).contains("Readable Chapter"))
            assertTrue(first.sectionIndex == 0)
            h.stop()
        }
    }

    private suspend fun ActivityScenario<MainActivity>.firstBlock(index: Int): String =
        org.json.JSONTokener(js("JSON.stringify(window.__readerTest.firstBlockText($index))")).nextValue().toString()
}
