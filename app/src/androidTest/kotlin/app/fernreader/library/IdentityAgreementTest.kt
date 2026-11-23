package app.fernreader.library

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fernreader.EpubReaderApp
import app.fernreader.MainActivity
import app.fernreader.TestBooks
import app.fernreader.core.data.SourceRef
import app.fernreader.core.epub.meta.BookIdentity
import app.fernreader.core.epub.openFileSource
import app.fernreader.core.epub.pkg.LoadResult
import app.fernreader.core.epub.pkg.PackageLoader
import app.fernreader.reader.OpenResult
import app.fernreader.reader.js
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
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
        // The identifier comes from the web side's package load (the same code `opened` reports), without laying each
        // book out: opening and rendering all ~40 corpus books one by one took ~105 s of the suite (C-049).
        val opener = (inst.targetContext.applicationContext as EpubReaderApp).graph.opener
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.reader.isReady.first { it } }
            for ((f, id) in expected) {
                val session = (opener.open(SourceRef.File(f.path)) as OpenResult.Ok).sessionId
                sc.js("window.__readerTest.identify(${JSONObject.quote(session)})")
                val got = withTimeout(20_000) {
                    while (true) {
                        val r = sc.js("JSON.stringify(window.__readerTest.identity(${JSONObject.quote(session)}))")
                        val text = org.json.JSONTokener(r).nextValue() as? String
                        if (text != null && text != "null") return@withTimeout JSONObject(text)
                        delay(20)
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                }
                opener.close(session)
                val identifier = if (got.isNull("identifier")) null else got.optString("identifier")
                if (got.has("error")) { mismatches += "${f.name}: web load failed: ${got.getString("error")}"; continue }
                val digest = openFileSource(f.path).use { app.fernreader.core.epub.zip.ZipReader.open(it).centralDirectoryDigest }
                val fromReader = BookIdentity.of(identifier, digest)
                if (fromReader != id) mismatches += "${f.name}: reader='$identifier'"
            }
        }
        assertEquals("identity mismatches", emptyList<String>(), mismatches)
    }
}
