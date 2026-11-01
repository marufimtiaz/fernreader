package app.epubreader.reader

import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.chunk.ChunkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** M8 8.3: chunk cache directories per book file and policy, protected as a group, deleted with their book (F-025). */
class ChunkDirsTest {
    private val root = Files.createTempDirectory("chunks").toFile()
    private val protected = mutableListOf<File>()
    private val dirs = ChunkDirs(root) { protected += it }
    private val book = SourceRef.File("/sdcard/Books/huge.epub")
    private val other = SourceRef.Content("content://provider/doc/7")

    @Test fun a_store_lives_in_its_own_protected_policy_directory() {
        val store = dirs.store(book, ChunkTestBook.small)
        store.write("cache.json", "{}".encodeToByteArray())
        val d = File(File(root, ChunkDirs.keyOf(book.key)), ChunkTestBook.small.key)
        assertEquals(listOf(d), protected) // setCacheBehaviorGroup on the directory the files are in
        assertEquals(1, d.listFiles()!!.size)
    }

    /** Review I1: a store only read (a complete cache, or nothing to read) makes no directory and no protect call. */
    @Test fun nothing_is_created_before_the_first_write() {
        val store = dirs.store(book, ChunkTestBook.small)
        assertEquals(null, store.read("cache.json"))
        store.clear()
        assertFalse(dirs.dirOf(book.key).exists())
        assertEquals(emptyList<File>(), protected)
        store.write("cache.json", "{}".encodeToByteArray())
        assertTrue(dirs.dir(book, ChunkTestBook.small).isDirectory)
        assertEquals(1, protected.size)
    }

    @Test fun a_new_policy_drops_the_other_policies_caches_of_that_file_only() {
        dirs.store(book, ChunkTestBook.small).write("x", byteArrayOf(1))
        dirs.store(other, ChunkTestBook.small).write("x", byteArrayOf(1))
        dirs.store(book, ChunkPolicy()).write("x", byteArrayOf(1))
        assertFalse(dirs.dir(book, ChunkTestBook.small).exists())
        assertTrue(dirs.dir(book, ChunkPolicy()).isDirectory)
        assertTrue(dirs.dir(other, ChunkTestBook.small).exists())
    }

    /** The M7 delete path hands over the book's `book_file.path`s, which are the opener's SourceRef keys. */
    @Test fun deleting_a_books_sources_removes_their_caches_and_nothing_else() {
        dirs.store(book, ChunkTestBook.small).write("x", byteArrayOf(1))
        dirs.store(other, ChunkTestBook.small).write("x", byteArrayOf(1))
        dirs.delete(listOf(book.path, "/never/opened.epub"))
        assertFalse(dirs.dirOf(book.key).exists())
        assertTrue(dirs.dir(other, ChunkTestBook.small).exists())
        dirs.delete(listOf(other.uri))
        assertEquals(0, root.listFiles()!!.size)
    }
}
