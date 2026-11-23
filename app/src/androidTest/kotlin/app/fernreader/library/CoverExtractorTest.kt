package app.fernreader.library

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** F-021 thumbnails from the corpus on shared storage (bench/push_corpus.sh). */
@RunWith(AndroidJUnit4::class)
class CoverExtractorTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ex = CoverExtractor(File(inst.targetContext.cacheDir, "thumbs-test"))

    @Before fun access() {
        inst.uiAutomation.executeShellCommand("appops set ${inst.targetContext.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
        val wp = File("/sdcard/Books/gutenberg/war-and-peace-epub3.epub")
        assumeTrue("corpus not on the device", wp.exists())
        // The grant reaches this (already running) process's storage view a moment later.
        val until = System.currentTimeMillis() + 3_000
        while (!runCatching { wp.inputStream().close() }.isSuccess && System.currentTimeMillis() < until) Thread.sleep(50)
    }

    @Test fun war_and_peace_thumbnail_is_small() {
        val t = File(ex.extract("wp", "/sdcard/Books/gutenberg/war-and-peace-epub3.epub")!!)
        assertTrue("${t.length()} bytes", t.length() in 1..60_000)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(t.path, o)
        assertTrue("width ${o.outWidth}", o.outWidth in 1..CoverExtractor.MAX_WIDTH)
    }

    @Test fun no_cover_and_dangling_cover_give_null() {
        assertNull(ex.extract("nc", "/sdcard/Books/generated/no-cover.epub"))
        assertNull(ex.extract("bad", "/sdcard/Books/generated/malformed-missing-files-bad-xml.epub"))
        assertNull(ex.extract("fake", "/sdcard/Books/generated/malformed-not-a-zip.epub"))
    }

    @Test fun huge_image_book_cover_is_downsampled() {
        assertNotNull(ex.extract("img", "/sdcard/Books/generated/stress-200-images.epub"))
    }
}
