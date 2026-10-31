package app.epubreader

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug-only. Serves files from the app's cache dir as content://app.epubreader.test.books/<name>[?pipe=1].
 * pipe=1 returns a non-seekable pipe (like many cloud providers). Lives in the debug build (not the test APK)
 * because test-APK components run in the test package's own process. Also used by M3's OpenWithTest.
 */
class TestBookProvider : ContentProvider() {
    private fun file(uri: Uri) = File(context!!.cacheDir, "testbooks/${uri.lastPathSegment}")
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/epub+zip"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = file(uri)
        if (uri.getQueryParameter("pipe") != "1") return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        val (r, w) = ParcelFileDescriptor.createPipe()
        thread { runCatching { ParcelFileDescriptor.AutoCloseOutputStream(w).use { out -> f.inputStream().use { it.copyTo(out) } } } } // reader may close early
        return r
    }
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any?>(uri.lastPathSegment, file(uri).length())) }
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
