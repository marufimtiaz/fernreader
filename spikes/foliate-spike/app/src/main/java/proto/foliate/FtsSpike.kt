package proto.foliate

import android.os.SystemClock
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import proto.common.Probe
import java.io.File
import java.util.zip.ZipFile

/** SPIKE (M0 S4): FTS5 availability, index build and query timings with the bundled SQLite driver. */
object FtsSpike {
    private val TAG = Regex("<[^>]+>")
    private val BLOCK = Regex("</(p|div|h[1-6]|li|blockquote|td|pre|section)>", RegexOption.IGNORE_CASE)
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")
    private fun decode(s: String) = ENTITY.replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
            else -> runCatching { String(Character.toChars(if (e[1] == 'x') e.drop(2).toInt(16) else e.drop(1).toInt())) }.getOrDefault(m.value)
        }
    }

    fun info(dbFile: File) {
        val c = BundledSQLiteDriver().open(dbFile.path)
        val v = c.prepare("SELECT sqlite_version()").use { it.step(); it.getText(0) }
        val opts = ArrayList<String>()
        c.prepare("PRAGMA compile_options").use { while (it.step()) opts += it.getText(0) }
        Probe.ev("fts_info", "version=$v options=${opts.joinToString(",")}")
        c.close()
    }

    fun build(book: String, dbFile: File) {
        dbFile.delete()
        val t0 = SystemClock.uptimeMillis(); var rows = 0; var chars = 0L
        val c = BundledSQLiteDriver().open(dbFile.path)
        c.execSQL("PRAGMA journal_mode=OFF")
        c.execSQL("PRAGMA synchronous=OFF")
        c.execSQL("CREATE VIRTUAL TABLE seg USING fts5(text, spine UNINDEXED, start UNINDEXED, tokenize = 'unicode61 remove_diacritics 2')")
        ZipFile(book).use { z ->
            val docs = z.entries().toList().filter { it.name.endsWith("html") || it.name.endsWith(".htm") }.sortedBy { it.name }
            c.execSQL("BEGIN")
            c.prepare("INSERT INTO seg(text, spine, start) VALUES (?, ?, ?)").use { st ->
                docs.forEachIndexed { si, e ->
                    val body = z.getInputStream(e).readBytes().toString(Charsets.UTF_8).substringAfter("<body", "")
                    var off = 0
                    for (block in BLOCK.split(body)) {
                        val text = decode(TAG.replace(block, ""))
                        if (text.isBlank()) continue
                        st.bindText(1, text); st.bindLong(2, si.toLong()); st.bindLong(3, off.toLong()); st.step(); st.reset()
                        off += text.length; rows++; chars += text.length
                    }
                }
            }
            c.execSQL("COMMIT")
        }
        val tIns = SystemClock.uptimeMillis()
        c.close()
        Probe.ev("fts_build", "book=${book.substringAfterLast('/')} rows=$rows chars=$chars ms=${SystemClock.uptimeMillis() - t0} insert_ms=${tIns - t0} bytes=${dbFile.length()}")
    }

    fun query(dbFile: File, q: String, limit: Int = 5000) {
        val t0 = SystemClock.uptimeMillis(); var n = 0; var rows = 0; var first = -1L
        val c = BundledSQLiteDriver().open(dbFile.path)
        c.prepare("SELECT spine, start, highlight(seg, 0, char(1), char(2)) FROM seg WHERE seg MATCH ? ORDER BY rowid LIMIT ?").use { st ->
            st.bindText(1, q); st.bindLong(2, limit.toLong())
            while (st.step()) {
                if (first < 0) first = SystemClock.uptimeMillis() - t0
                rows++; n += st.getText(2).count { it == '\u0001' }
                if (n >= limit) break
            }
        }
        c.close()
        Probe.ev("fts_query", "q=$q rows=$rows hits=$n first_ms=$first done_ms=${SystemClock.uptimeMillis() - t0}")
    }
}
