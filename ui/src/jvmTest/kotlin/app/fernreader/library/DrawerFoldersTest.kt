package app.fernreader.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** C-074: the folder rows of the navigation drawer. */
class DrawerFoldersTest {
    private val r = "/storage/emulated/0"
    /** [counts] folders, each holding its own distinct books. */
    private fun build(counts: Map<String, Int>, expanded: Set<String>) =
        DrawerFolders.build(counts.mapValues { (f, n) -> (1..n).mapTo(HashSet()) { "$f#$it" } }, expanded)
    /** The F-017 folder set (corpus.md section 4), books per folder. */
    private val set = mapOf(
        "$r/Download" to 6, "$r/Documents/Books" to 2, "$r/Books" to 1, "$r/Books/ToDelete" to 1, "$r/Books/Fiction/Classics/Old" to 1,
        "$r/Livres Été 2026" to 1, "$r/.hidden" to 1, "$r/NoMedia" to 1, "$r/Android/media/com.example.test" to 1,
    )

    @Test fun top_level_only_until_a_folder_is_expanded() {
        val d = build(set, expanded = emptySet())
        assertEquals(listOf(".hidden", "Android", "Books", "Documents", "Download", "Livres Été 2026", "NoMedia"), d.rows.map { it.node.name })
        assertTrue(d.rows.all { it.node.depth == 0 })
        fun row(name: String) = d.rows.single { it.node.name == name }.node
        assertTrue(row("Android").hasChildren); assertTrue(row("Books").hasChildren); assertFalse(row("NoMedia").hasChildren)
        assertFalse(row("Books").expanded)
        assertEquals(3, row("Books").total) // with its subfolders
        val open = build(set, expanded = setOf("$r/Books", "$r/Documents"))
        assertEquals(listOf(".hidden", "Android", "Books", "Fiction", "ToDelete", "Documents", "Books", "Download", "Livres Été 2026", "NoMedia"),
            open.rows.map { it.node.name })
        assertTrue(open.rows.single { it.node.name == "Fiction" }.node.let { it.depth == 1 && !it.expanded && it.hasChildren })
    }

    @Test fun same_named_folders_show_their_parent_path() {
        val d = build(set, expanded = setOf("$r/Documents"))
        val books = d.rows.filter { it.node.name == "Books" }
        assertEquals(listOf("$r/Books", "$r/Documents/Books"), books.map { it.node.path })
        assertEquals(listOf("Device storage", "Documents"), books.map { it.parentLine })
        assertNull(d.rows.single { it.node.name == "Download" }.parentLine)
        // told apart even while one of them is folded away
        assertEquals("Device storage", build(set, emptySet()).rows.single { it.node.name == "Books" }.parentLine)
        // names that differ only in case count as the same name; another volume's top folder names its volume
        val other = build(mapOf("$r/books" to 1, "/storage/1A2B-3C4D/Books" to 1), expanded = setOf("/storage/1A2B-3C4D"))
        assertEquals(listOf("Device storage", "Storage 1A2B-3C4D"), other.rows.filter { it.node.name.equals("books", true) }.map { it.parentLine })
    }

    @Test fun more_than_fifteen_rows_end_with_show_all() {
        val many = (1..16).associate { "$r/F%02d".format(it) to 1 }
        val d = build(many, emptySet())
        assertEquals(15, d.rows.size); assertTrue(d.showAll)
        assertEquals("F15", d.rows.last().node.name)
        val fifteen = build(many - "$r/F16", emptySet())
        assertEquals(15, fifteen.rows.size); assertFalse(fifteen.showAll)
        // expanding counts: the visible rows are what is capped
        val nested = build(mapOf("$r/A" to 1) + (1..15).associate { "$r/A/S%02d".format(it) to 1 }, setOf("$r/A"))
        assertEquals(15, nested.rows.size); assertTrue(nested.showAll)
    }

    @Test fun no_books_no_rows() {
        val d = build(emptyMap(), emptySet())
        assertTrue(d.rows.isEmpty()); assertFalse(d.showAll)
    }
}
