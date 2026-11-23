package app.fernreader.core.search

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL

data class Fts5Report(val sqliteVersion: String, val fts5: Boolean, val diacriticsFolded: Boolean)

/** Verifies that the bundled SQLite supports what the search index needs (FTS5 + unicode61 remove_diacritics 2). */
object Fts5Capability {
    fun check(dbPath: String): Fts5Report {
        val c = BundledSQLiteDriver().open(dbPath)
        try {
            val version = c.prepare("SELECT sqlite_version()").use { it.step(); it.getText(0) }
            val fts5 = runCatching {
                c.execSQL("DROP TABLE IF EXISTS fts_probe")
                c.execSQL("CREATE VIRTUAL TABLE fts_probe USING fts5(text, tokenize = 'unicode61 remove_diacritics 2')")
            }.isSuccess
            if (!fts5) return Fts5Report(version, false, false)
            c.execSQL("INSERT INTO fts_probe(text) VALUES ('the battle of Borodinó')")
            val folded = c.prepare("SELECT count(*) FROM fts_probe WHERE fts_probe MATCH 'borodino'").use { it.step(); it.getLong(0) == 1L }
            c.execSQL("DROP TABLE fts_probe")
            return Fts5Report(version, true, folded)
        } finally {
            c.close()
        }
    }
}
