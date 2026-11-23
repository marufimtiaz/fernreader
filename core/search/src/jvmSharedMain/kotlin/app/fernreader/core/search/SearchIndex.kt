package app.fernreader.core.search

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.fernreader.core.epub.text.TextExtractor
import java.io.File

internal data class IndexMeta(val tag: String, val digest: String, val sections: Int, val sectionsDone: Int, val complete: Boolean)

internal class IndexRow(val start: Int, val text: String)

/**
 * One book's FTS5 file (see M5 design): `seg` holds a row per text segment in book order, `meta` says how far the build
 * got. Rows and `sectionsDone` change in one transaction, so a killed build resumes at a section boundary.
 */
internal class SearchIndex private constructor(private val db: SQLiteConnection) : AutoCloseable {
    fun meta(): IndexMeta = readMeta(db) ?: throw SQLiteException("index has no meta")

    /** Adds [rows] of spine item [spine] and records that sections up to it are done; [complete] only for the last one. */
    fun addSection(spine: Int, rows: List<IndexRow>, complete: Boolean) {
        db.execSQL("BEGIN IMMEDIATE")
        try {
            if (rows.isNotEmpty()) db.prepare("INSERT INTO seg(text, spine, start) VALUES (?, ?, ?)").use { st ->
                for (r in rows) {
                    st.bindText(1, r.text); st.bindLong(2, spine.toLong()); st.bindLong(3, r.start.toLong())
                    st.step(); st.reset()
                }
            }
            setMeta(db, "sectionsDone", (spine + 1).toString())
            if (complete) setMeta(db, "complete", "1")
            db.execSQL("COMMIT")
        } catch (t: Throwable) {
            runCatching { db.execSQL("ROLLBACK") }
            throw t
        }
    }

    fun markComplete() = setMeta(db, "complete", "1")

    override fun close() = db.close()

    companion object {
        /** Schema/format version of the file; part of the stored tag. */
        const val VERSION = 1
        private const val BUSY_MS = 5000

        fun formatTag() = "$VERSION.${TextExtractor.VERSION}"

        /**
         * Opens the index at [path] if it is valid for this [tag], [digest] and section count; otherwise (missing, other
         * version, other file, damaged) deletes it and starts an empty one (C-009).
         */
        fun openOrReset(path: String, digest: String, sections: Int, tag: String): SearchIndex {
            if (File(path).exists()) {
                val db = BundledSQLiteDriver().open(path)
                val ok = try {
                    readMeta(db)?.let { it.tag == tag && it.digest == digest && it.sections == sections } == true
                } catch (e: SQLiteException) {
                    false
                }
                if (ok) { configure(db); return SearchIndex(db) }
                db.close()
                deleteFiles(path)
            }
            File(path).absoluteFile.parentFile?.mkdirs()
            val db = BundledSQLiteDriver().open(path)
            try {
                configure(db)
                db.execSQL("BEGIN IMMEDIATE")
                db.execSQL("""CREATE VIRTUAL TABLE seg USING fts5(text, spine UNINDEXED, start UNINDEXED, tokenize = "unicode61 remove_diacritics 2")""")
                db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
                setMeta(db, "extractorVersion", tag); setMeta(db, "digest", digest); setMeta(db, "sections", sections.toString())
                setMeta(db, "sectionsDone", "0"); setMeta(db, "complete", "0")
                db.execSQL("COMMIT")
            } catch (t: Throwable) {
                db.close(); deleteFiles(path); throw t
            }
            return SearchIndex(db)
        }

        /** The stored meta, or null if there is no usable index file. Cheap; does not validate the digest. */
        fun peek(path: String): IndexMeta? {
            if (!File(path).exists()) return null
            val db = try { BundledSQLiteDriver().open(path) } catch (e: SQLiteException) { return null }
            return try {
                db.execSQL("PRAGMA busy_timeout=$BUSY_MS")
                readMeta(db)
            } catch (e: SQLiteException) { null } finally { db.close() }
        }

        /**
         * A connection for queries; null if the file is missing or is not the index of the file with [digest]. Throws
         * [SQLiteException] if it cannot be read right now (locked while a builder closes, or damaged): the caller may retry.
         */
        fun openReader(path: String, digest: String, tag: String = formatTag()): SQLiteConnection? {
            if (!File(path).exists()) return null
            val db = BundledSQLiteDriver().open(path)
            try {
                db.execSQL("PRAGMA busy_timeout=$BUSY_MS") // before the first read: the builder's close holds locks briefly
                val m = readMeta(db)
                if (m != null && m.tag == tag && m.digest == digest) return db
            } catch (t: Throwable) {
                db.close(); throw t
            }
            db.close()
            return null
        }

        fun deleteFiles(path: String) {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) File(path + suffix).delete()
        }

        /** WAL so readers see each committed section while the builder writes; NORMAL sync is safe with WAL and the file is a rebuildable cache. */
        private fun configure(db: SQLiteConnection) {
            db.execSQL("PRAGMA busy_timeout=$BUSY_MS")
            db.prepare("PRAGMA journal_mode=WAL").use { it.step() }
            db.execSQL("PRAGMA synchronous=NORMAL")
        }

        private fun readMeta(db: SQLiteConnection): IndexMeta? {
            val m = HashMap<String, String>()
            db.prepare("SELECT key, value FROM meta").use { st -> while (st.step()) m[st.getText(0)] = st.getText(1) }
            return IndexMeta(
                tag = m["extractorVersion"] ?: return null,
                digest = m["digest"] ?: return null,
                sections = m["sections"]?.toIntOrNull() ?: return null,
                sectionsDone = m["sectionsDone"]?.toIntOrNull() ?: return null,
                complete = m["complete"] == "1",
            )
        }

        private fun setMeta(db: SQLiteConnection, key: String, value: String) {
            db.prepare("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)").use { st ->
                st.bindText(1, key); st.bindText(2, value); st.step()
            }
        }
    }
}
