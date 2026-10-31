package app.epubreader.library

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epubreader.MainActivity
import app.epubreader.TestBooks
import app.epubreader.core.data.SourceRef
import app.epubreader.reader.ReaderError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** R-008: the open book's file is replaced or deleted → message, book closed, no crash. */
@RunWith(AndroidJUnit4::class)
class FileReplacedUnderReaderTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun underReader(name: String, change: (File) -> Unit): Unit = runBlocking {
        val f = TestBooks.write(ctx, name, TestBooks.epub(id = "urn:uuid:$name", chapters = 3, paragraphs = 60))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.reader.isReady.first { it } }
            sc.onActivity { it.openBook(SourceRef.File(f.path)) }
            withTimeout(20_000) { a.controller.state.first { it.open && !it.loading } }
            change(f)
            val s = withTimeout(5_000) { a.controller.state.first { it.error != null } }
            assertEquals(ReaderError.FileChanged, s.error)
            sc.onActivity { it.goBack() } // back to the library, still running
            Unit
        }
    }

    @Test fun replaced(): Unit = underReader("r8-replace.epub") { it.writeBytes(TestBooks.epub(id = "urn:uuid:other", title = "Other")) }
    @Test fun deleted(): Unit = underReader("r8-delete.epub") { it.delete() }
    @Test fun replaced_by_rename(): Unit = underReader("r8-rename.epub") { f ->
        val tmp = File(f.parentFile, "r8-rename.tmp").apply { writeBytes(TestBooks.epub(id = "urn:uuid:renamed-over")) }
        check(tmp.renameTo(f))
    }
}
