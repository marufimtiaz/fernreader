package app.fernreader.robustness

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fernreader.EpubReaderApp
import app.fernreader.MainActivity
import app.fernreader.core.data.SourceRef
import app.fernreader.core.library.ScanScope
import app.fernreader.ui.Copy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * R-010 (F-010 check) on the folder set placed by bench/push_corpus.sh: names with spaces and non-ASCII characters,
 * dot-folders, .nomedia, app media folders, upper-case extensions and deep nesting are found by the scan, shown with
 * their title and folder, and open in the reader.
 */
@RunWith(AndroidJUnit4::class)
class FolderSetTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (inst.targetContext.applicationContext as EpubReaderApp).graph
    private val sd = "/storage/emulated/0"

    /** Placement per spec corpus.md section 4 (livetest.epub is pushed later by the F-012 test, so it is not here). */
    private val placed = mapOf(
        "download" to "Download/download.epub",
        "upper" to "Download/UPPER.EPUB",
        "documents" to "Documents/Books/documents.epub",
        "nested3" to "Books/Fiction/Classics/Old/nested3.epub",
        "unicode" to "Livres Été 2026/unicode.epub",
        "hidden" to ".hidden/hidden.epub",
        "nomedia" to "NoMedia/nomedia.epub",
        "androidmedia" to "Android/media/com.example.test/androidmedia.epub",
        "deletedfolder" to "Books/ToDelete/deletedfolder.epub",
        "insidecustom" to "Books/insidecustom.epub",
        "outsidecustom" to "Download/outsidecustom.epub",
    )

    @Test fun every_placed_folder_book_is_found_shown_and_opens(): Unit = runBlocking {
        inst.uiAutomation.executeShellCommand("appops set ${inst.targetContext.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
        for ((_, rel) in placed) check(File("$sd/$rel").isFile) { "folder set not pushed (bench/push_corpus.sh): $rel" }
        graph.scanner.scan(ScanScope.Everywhere)
        val books = graph.library.library().first()
        for ((name, rel) in placed) {
            val path = "$sd/$rel"
            val b = books.firstOrNull { path in it.paths || it.path == path }
            assertTrue("not in the library: $rel", b != null)
            assertEquals(rel, "Folder Test $name", b!!.title)
            assertEquals(rel, File(rel).parent ?: "/", Copy.displayFolder(File(path).parent!!))
        }
        // dup.epub and its byte-identical copy are one book (C-009), with both paths
        val dup = books.single { it.title == "Folder Test dup" }
        assertTrue(dup.paths.toString(), "$sd/Download/dup.epub" in dup.paths || dup.path == "$sd/Download/dup.epub")
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.reader.isReady.first { it } }
            for ((name, rel) in placed) {
                sc.onActivity { it.openBook(SourceRef.File("$sd/$rel")) }
                val s = withTimeout(20_000) { a.controller.state.first { (it.open && !it.loading && it.title.isNotEmpty()) || it.error != null } }
                assertNull("$rel: ${s.error}", s.error)
                assertEquals(rel, "Folder Test $name", s.title)
                sc.onActivity { it.goBack() }
            }
        }
    }
}
