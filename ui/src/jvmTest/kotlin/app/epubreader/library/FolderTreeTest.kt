package app.epubreader.library

import kotlin.test.Test
import kotlin.test.assertEquals

class FolderTreeTest {
    private val r = "/storage/emulated/0"

    @Test fun tree_order_counts_and_collapse() {
        val folders = mapOf("$r/Livres Été 2026" to 1, "$r/.hidden" to 1, "$r/Android/media/com.example.test" to 1, "$r/a/b/c" to 2, "$r/a" to 1)
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
        val all = FolderTree.build(mapOf(r to 2, "/storage/1A2B-3C4D/Books" to 1), emptySet())
        assertEquals(listOf(r, "/storage/1A2B-3C4D", "/storage/1A2B-3C4D/Books"), all.map { it.path })
        assertEquals(0, all.first().depth)
    }
}
