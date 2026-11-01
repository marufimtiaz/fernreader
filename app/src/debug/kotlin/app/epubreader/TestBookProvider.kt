package app.epubreader

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import kotlin.concurrent.thread

/**
 * Debug-only. Serves files from the app's cache dir as content://app.fernreader.test.books/<name>[?pipe=1].
 * pipe=1 returns a non-seekable pipe (like many cloud providers). Lives in the debug build (not the test APK)
 * because test-APK components run in the test package's own process. Also used by M3's OpenWithTest.
 * Not exported (only this app's process uses it); names with a path part are rejected.
 */
class TestBookProvider : ContentProvider() {
    /** Plain file names only: lastPathSegment is percent-decoded, so "..%2F" would otherwise leave testbooks/. */
    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment
        if (name.isNullOrEmpty() || name == "." || name == ".." || File(name).name != name) {
            throw FileNotFoundException("not a test book name: $name")
        }
        return File(context!!.cacheDir, "testbooks/$name")
    }
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
