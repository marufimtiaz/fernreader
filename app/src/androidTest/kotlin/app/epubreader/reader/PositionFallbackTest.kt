package app.epubreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epubreader.EpubReaderApp
import app.epubreader.MainActivity
import app.epubreader.TestBooks
import app.epubreader.core.data.SourceRef
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** C-009 / C-029: a saved position that no longer resolves lands on the nearest valid place in its chapter. */
@RunWith(AndroidJUnit4::class)
class PositionFallbackTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun position_past_the_end_of_its_chapter_opens_on_that_chapters_last_page(): Unit = runBlocking {
        val n = System.nanoTime()
        val book = TestBooks.write(ctx, "fallback.epub", TestBooks.epub(id = "urn:uuid:fallback-$n", chapters = 3, paragraphs = 60))
        val other = TestBooks.write(ctx, "fallback-other.epub", TestBooks.epub(id = "urn:uuid:fallback-other-$n"))
        val ref = SourceRef.File(book.path)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.reader.isReady.first { it } }
            val relocates = a.reader.events.filterIsInstance<FromWeb.Relocate>()
            var next = async(start = CoroutineStart.UNDISPATCHED) { relocates.first() }
            sc.onActivity { it.openBook(ref) }
            withTimeout(10_000) { next.await() }
            withTimeout(5_000) { a.controller.state.first { !it.loading && it.toc.isNotEmpty() } }
            next = async(start = CoroutineStart.UNDISPATCHED) { relocates.first { it.sectionIndex == 1 } }
            sc.onActivity { it.reader.send(ToWeb.GoTo(target = a.controller.state.value.toc.first { t -> t.label == "Chapter 2" }.href)) }
            val inChapter = withTimeout(5_000) { next.await() }

            val graph = (a.application as EpubReaderApp).graph
            val bookId = graph.library.knownBookId(ref)!!
            withTimeout(3_000) { while (graph.library.position(bookId)?.cfi != inChapter.cfi) delay(20) }
            sc.onActivity { it.goBack() }
            // Another book in between, so the next open is a cold one (no warm view kept at the old place).
            next = async(start = CoroutineStart.UNDISPATCHED) { relocates.first() }
            sc.onActivity { it.openBook(SourceRef.File(other.path)) }
            withTimeout(10_000) { next.await() }
            sc.onActivity { it.goBack() }
            delay(500) // close-out stored the last position of each book

            // The file "changed": the stored path now points past the end of the chapter's body.
            val saved = graph.library.position(bookId)!!
            val broken = "epubcfi(" + saved.cfi.removePrefix("epubcfi(").substringBefore('!') + "!/4/9999)"
            graph.db.positionDao().put(bookId, broken, saved.fraction, saved.spineIndex, saved.updatedAt + 1)

            next = async(start = CoroutineStart.UNDISPATCHED) { relocates.first() }
            sc.onActivity { it.openBook(ref) }
            val landed = withTimeout(10_000) { next.await() }
            assertEquals(1, landed.sectionIndex)
            val at = sc.hook("page()")
            assertEquals("landed on $at", at.getInt("textPages"), at.getInt("page"))
        }
    }

    /** A position foliate itself saved on an image-only page (War and Peace's cover) has no text to scroll to. */
    @Test fun position_on_an_image_only_page_opens(): Unit = runBlocking {
        val wp = warAndPeace()
        val ref = SourceRef.File(wp.path)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.reader.isReady.first { it } }
            val relocates = a.reader.events.filterIsInstance<FromWeb.Relocate>()
            var next = async(start = CoroutineStart.UNDISPATCHED) { relocates.first() }
            sc.onActivity { it.openBook(ref) }
            withTimeout(15_000) { next.await() }
            val graph = (a.application as EpubReaderApp).graph
            val bookId = graph.library.knownBookId(ref)!!
            sc.onActivity { it.goBack() }
            delay(500)
            val saved = graph.library.position(bookId)!!
            graph.db.positionDao().put(bookId, "epubcfi(/6/2!/4/2,,/2/2)", 0.0, 0, saved.updatedAt + 1)
            val other = TestBooks.write(ctx, "cover-other.epub", TestBooks.epub(id = "urn:uuid:cover-other-${System.nanoTime()}"))
            next = async(start = CoroutineStart.UNDISPATCHED) { relocates.first() }
            sc.onActivity { it.openBook(SourceRef.File(other.path)) } // so the next open is a cold one
            withTimeout(10_000) { next.await() }
            sc.onActivity { it.goBack() }

            next = async(start = CoroutineStart.UNDISPATCHED) { relocates.first() }
            sc.onActivity { it.openBook(ref) }
            assertEquals(0, withTimeout(10_000) { next.await() }.sectionIndex)
            withTimeout(5_000) { a.controller.state.first { !it.loading } }
        }
    }
}
