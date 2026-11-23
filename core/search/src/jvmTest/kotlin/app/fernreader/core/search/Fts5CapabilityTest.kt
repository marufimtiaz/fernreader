package app.fernreader.core.search

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class Fts5CapabilityTest {
    @Test fun bundled_sqlite_has_fts5_with_diacritic_folding() {
        val r = Fts5Capability.check(Files.createTempDirectory("fts").resolve("c.db").toString())
        assertTrue(r.fts5, "FTS5 missing in ${r.sqliteVersion}")
        assertTrue(r.diacriticsFolded, "remove_diacritics 2 did not match Borodinó for borodino")
    }
}
