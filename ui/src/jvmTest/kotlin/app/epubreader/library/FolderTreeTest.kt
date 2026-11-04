package app.epubreader.library

import app.epubreader.ui.Copy
import kotlin.test.Test
import kotlin.test.assertEquals

class FolderTreeTest {
    private val r = "/storage/emulated/0"

    /** [counts]' folders, each holding its own distinct books. */
    private fun books(vararg counts: Pair<String, Int>) = counts.toMap().mapValues { (f, n) -> (1..n).mapTo(HashSet()) { "$f#$it" } as Set<String> }

    @Test fun tree_order_counts_and_collapse() {
        val folders = books("$r/Livres Été 2026" to 1, "$r/.hidden" to 1, "$r/Android/media/com.example.test" to 1, "$r/a/b/c" to 2, "$r/a" to 1)
        val all = FolderTree.build(folders, collapsed = emptySet())
        assertEquals(listOf(".hidden", "a", "b", "c", "Android", "media", "com.example.test", "Livres Été 2026"), all.map { it.name })
        assertEquals(listOf(1, 3, 2, 2, 1, 1, 1, 1), all.map { it.total })
        assertEquals(listOf(1, 1, 0, 2, 0, 0, 1, 1), all.map { it.direct })
        assertEquals("a/b", all.single { it.name == "c" }.parent)
        val collapsed = FolderTree.build(folders, collapsed = setOf("$r/a"))
        assertEquals(listOf(".hidden", "a", "Android", "media", "com.example.test", "Livres Été 2026"), collapsed.map { it.name })
        assertEquals(true, collapsed.single { it.name == "a" }.hasChildren)
    }

    @Test fun books_in_the_storage_root_and_on_another_volume() {
        val all = FolderTree.build(books(r to 2, "/storage/1A2B-3C4D/Books" to 1), emptySet())
        assertEquals(listOf(r, "/storage/1A2B-3C4D", "/storage/1A2B-3C4D/Books"), all.map { it.path })
        assertEquals(0, all.first().depth)
    }

    /** C-090: X holds a book, X/Y a byte-identical (or renamed) copy: one book, so one in each row and in X's total. */
    @Test fun a_copy_in_a_subfolder_is_counted_once() {
        val all = FolderTree.build(mapOf("$r/Books/X" to setOf("alice"), "$r/Books/X/Y" to setOf("alice"), "$r/Books" to setOf("emma")), emptySet())
        val row = all.associateBy { it.name }
        assertEquals(1 to 1, row.getValue("X").let { it.total to it.direct })
        assertEquals(1, row.getValue("Y").total)
        assertEquals(2, row.getValue("Books").total) // emma and alice, not 3
        assertEquals("1 · 1 here", row.getValue("X").let { Copy.folderCount(it.direct, it.total, it.hasChildren) })
        assertEquals("1", row.getValue("Y").let { Copy.folderCount(it.direct, it.total, it.hasChildren) })
    }

    @Test fun folder_count_label() {
        assertEquals("41", Copy.folderCount(direct = 0, total = 41, hasChildren = true))
        assertEquals("41", Copy.folderCount(direct = 41, total = 41, hasChildren = false))
        assertEquals("41 · 1 here", Copy.folderCount(direct = 1, total = 41, hasChildren = true))
    }
}
