package app.epubreader.library

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.epubreader.MainActivity
import app.epubreader.TestBooks
import app.epubreader.reader.ReaderError
import app.epubreader.ui.Copy
import app.epubreader.ui.Route
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** F-013 open-with, R-002 unreadable files via open-with, Review Focus 2 (content:// with no file path). */
@RunWith(AndroidJUnit4::class)
class OpenWithTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val device = UiDevice.getInstance(inst)

    private fun view(uri: Uri) = Intent(Intent.ACTION_VIEW, uri, ctx, MainActivity::class.java).setType(null).apply { setDataAndType(uri, "application/epub+zip") }

    private fun opens(uri: Uri) = runBlocking {
        ActivityScenario.launch<MainActivity>(view(uri)).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it; assertEquals(Route.Reader, it.nav.top) } // straight to the reader, no library first
            withTimeout(20_000) { a.controller.state.first { it.open && !it.loading && it.error == null } }
            sc.onActivity { it.goBack() } // Back returns to the caller: the activity finishes
            withTimeout(5_000) { while (sc.state != Lifecycle.State.DESTROYED) kotlinx.coroutines.delay(20) }
        }
    }

    @Test fun fileUri() {
        inst.uiAutomation.executeShellCommand("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
        val f = TestBooks.write(ctx, "ow-file.epub", TestBooks.epub(id = "urn:uuid:ow-file"))
        opens(Uri.fromFile(f))
    }

    @Test fun contentUriWithoutPath() {
        TestBooks.write(ctx, "ow-content.epub", TestBooks.epub(id = "urn:uuid:ow-content"))
        opens(Uri.parse("content://app.epubreader.test.books/ow-content.epub?pipe=1"))
    }

    @Test fun malformedEachFile() {
        val bad = mapOf(
            "notzip.epub" to "not an epub at all\n".toByteArray(),
            "truncated.epub" to TestBooks.epub().let { it.copyOf(it.size * 2 / 3) },
            "empty.epub" to ByteArray(0),
        )
        for ((name, bytes) in bad) {
            TestBooks.write(ctx, name, bytes)
            ActivityScenario.launch<MainActivity>(view(Uri.parse("content://app.epubreader.test.books/$name"))).use { sc ->
                lateinit var a: MainActivity
                sc.onActivity { a = it }
                val err = runBlocking { withTimeout(20_000) { a.controller.state.first { it.error != null }.error } }
                assertTrue("$name: $err", err is ReaderError.Open || err is ReaderError.Engine)
                assertNotNull(name, device.wait(Until.findObject(By.text(Copy.back)), 5_000))
                device.findObject(By.text(Copy.back)).click() // the error screen's button returns to the caller
                runBlocking { withTimeout(5_000) { while (sc.state != Lifecycle.State.DESTROYED) kotlinx.coroutines.delay(20) } }
            }
        }
    }

    // Final review I4: open-with while the app runs reuses the one activity (one reader engine, one opener); Back
    // returns to the library and moves the app behind the calling app.
    @Test fun whileRunning_reusesTheActivity(): Unit = runBlocking {
        val a1 = TestBooks.write(ctx, "ow-a.epub", TestBooks.epub(id = "urn:uuid:ow-a"))
        TestBooks.write(ctx, "ow-b.epub", TestBooks.epub(id = "urn:uuid:ow-b", title = "Book B"))
        // Not closed through the scenario: it cannot follow an activity moved behind another app's task.
        val sc = ActivityScenario.launch(MainActivity::class.java)
        lateinit var first: MainActivity
        sc.onActivity { first = it; it.openBook(app.epubreader.core.data.SourceRef.File(a1.path)) }
        withTimeout(20_000) { first.controller.state.first { it.open && !it.loading && it.error == null } }
        ctx.startActivity(view(Uri.parse("content://app.epubreader.test.books/ow-b.epub")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        withTimeout(20_000) { first.controller.state.first { it.title == "Book B" && !it.loading } }
        inst.runOnMainSync { assertEquals(Route.Reader, first.nav.top); first.goBack() }
        inst.runOnMainSync { assertTrue(first.nav.top != Route.Reader); assertTrue(!first.isFinishing); first.finish() }
        withTimeout(5_000) { while (!first.isDestroyed) kotlinx.coroutines.delay(20) }
    }
}
