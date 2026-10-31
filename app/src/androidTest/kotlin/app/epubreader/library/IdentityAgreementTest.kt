package app.epubreader.library

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epubreader.MainActivity
import app.epubreader.TestBooks
import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.meta.BookIdentity
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.pkg.LoadResult
import app.epubreader.core.epub.pkg.PackageLoader
import app.epubreader.reader.bridge.FromWeb
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Review Focus 1: the identity the reader records on open (foliate's identifier, M2) equals the one the scanner
 * computes in Kotlin (PackageLoader), for every reflowable corpus book on the device and for a generated book whose
 * identifier has newlines and tabs inside. A mismatch would show a book read in M2 twice and lose its position.
 */
@RunWith(AndroidJUnit4::class)
class IdentityAgreementTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun kotlinIdentity(f: File): String? = openFileSource(f.path).use { src ->
        val (zip, r) = PackageLoader.open(src)
        val ok = r as? LoadResult.Ok ?: return null
        if (ok.pkg.fixedLayout) return null
        BookIdentity.of(ok.pkg.uniqueId, zip!!.centralDirectoryDigest)
    }

    @Test fun reader_and_scanner_agree(): Unit = runBlocking {
        inst.uiAutomation.executeShellCommand("appops set ${inst.targetContext.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
        val books = File("/sdcard/Books").walkTopDown().filter { it.isFile && it.name.endsWith(".epub", ignoreCase = true) }.toMutableList()
        books += TestBooks.write(inst.targetContext, "ws-id.epub", TestBooks.epub(id = "\n   urn:uuid:ws-agree \t\n  x  "))
        val expected = books.mapNotNull { f -> kotlinIdentity(f)?.let { f to it } }
        // With the corpus pushed (bench/push_corpus.sh) every reflowable corpus book is compared, not only the generated one.
        if (File("/sdcard/Books/gutenberg").isDirectory) assertTrue("only ${expected.size} books readable", expected.size >= 30)
        println("IdentityAgreementTest: comparing ${expected.size} books")
        val mismatches = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.reader.isReady.first { it } }
            val ch = Channel<FromWeb>(Channel.UNLIMITED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { a.reader.events.collect { ch.send(it) } }
            for ((f, id) in expected) {
                sc.onActivity { it.openBook(SourceRef.File(f.path)) }
                val opened = withTimeout(20_000) { while (true) { val e = ch.receive(); if (e is FromWeb.Opened) return@withTimeout e }; error("") }
                val digest = openFileSource(f.path).use { app.epubreader.core.epub.zip.ZipReader.open(it).centralDirectoryDigest }
                val fromReader = BookIdentity.of(opened.identifier, digest)
                if (fromReader != id) mismatches += "${f.name}: reader='${opened.identifier}'"
                sc.onActivity { it.goBack() }
            }
            job.cancel()
        }
        assertEquals("identity mismatches", emptyList<String>(), mismatches)
    }
}
