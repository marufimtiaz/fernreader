package app.fernreader.core.library

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileWalkerTest {
    private val root = Files.createTempDirectory("walk").toFile()
    private fun epub(rel: String) = File(root, rel).apply { parentFile.mkdirs(); writeText("x") }
    private fun walk(w: StorageWalker = FileStorageWalker(root.path)) = runBlocking { mutableListOf<FoundFile>().also { l -> w.walk(ScanScope.Everywhere) { l += it } } }

    @Test fun unreadable_and_vanishing_dirs_skipped() {
        epub("a/ok.epub"); epub("locked/in.epub"); epub("gone/g.epub"); epub("z/last.epub")
        val locked = File(root, "locked").apply { setReadable(false) }
        val asRoot = System.getProperty("user.name") == "root"
        // A folder deleted during the walk: the walker lists it after its parent was listed.
        val w = object : StorageWalker by FileStorageWalker(root.path) {}
        val found = runBlocking {
            mutableListOf<FoundFile>().also { l -> w.walk(ScanScope.Everywhere) { l += it; if (it.path.endsWith("last.epub") || it.path.endsWith("ok.epub")) File(root, "gone").deleteRecursively() } }
        }.map { File(it.path).name }
        locked.setReadable(true)
        assertTrue("ok.epub" in found && "last.epub" in found, "$found")
        if (!asRoot) assertTrue("in.epub" !in found)
    }

    @Test fun symlink_loop_terminates() {
        epub("a/book.epub")
        Files.createSymbolicLink(File(root, "a/loop").toPath(), File(root, "a").toPath())
        assertEquals(1, walk().count { it.path.endsWith("book.epub") })
    }

    @Test fun first_dirs_walked_first() {
        epub("Android/media/x/m.epub"); epub("Books/b.epub"); epub("Download/d.epub")
        val names = walk().map { File(it.path).name }
        assertEquals("b.epub", names.first()); assertEquals(3, names.size)
    }

    @Test fun dot_folders_upper_case_and_unicode() {
        epub(".hidden/h.epub"); epub("Livres Été 2026/u.EPUB"); epub("plain.txt")
        assertEquals(setOf("h.epub", "u.EPUB"), walk().map { File(it.path).name }.toSet())
    }
}

/** C-075: a walk lists only directories that changed since the last walk (one stat for each unchanged one). */
class FileWalkerCacheTest {
    private val root = Files.createTempDirectory("walkc").toFile()
    private fun epub(rel: String, text: String = "x") = File(root, rel).apply { parentFile.mkdirs(); writeText(text) }
    private val listed = mutableListOf<String>()
    /** Every directory looks older than the racy window, unless [future] is lowered. */
    private var future = 60_000L
    private val w = FileStorageWalker(root.path, list = { d -> listed += d.path; d.listFiles() }, nowMs = { System.currentTimeMillis() + future })
    private fun walk() = runBlocking { listed.clear(); mutableListOf<FoundFile>().also { l -> w.walk(ScanScope.Everywhere) { l += it } } }
    private fun rel(f: String) = f.removePrefix(root.path).trimStart('/')

    init {
        epub("Books/a.epub"); epub("Download/d.epub"); epub("Pictures/x/y/z/p.jpg")
        for (i in 1..20) epub("Pictures/n$i/f.jpg")
    }

    @Test fun unchanged_tree_is_not_listed_again() {
        val first = walk()
        assertTrue(listed.size > 20)
        val second = walk()
        assertEquals(first.map { it.path }.toSet(), second.map { it.path }.toSet())
        assertEquals(listOf(""), listed.map(::rel)) // only the scan root's "can it be listed" check
    }

    @Test fun new_book_in_a_new_nested_folder_is_found_listing_only_changed_dirs() {
        walk()
        epub("Documents/New/Deeper/n.epub")
        val found = walk().map { rel(it.path) }
        assertTrue("Documents/New/Deeper/n.epub" in found, "$found")
        assertEquals(setOf("", "Documents/New", "Documents/New/Deeper"), listed.map(::rel).toSet() - setOf("Documents"))
        assertTrue("Pictures" !in listed.map(::rel))
    }

    @Test fun deleted_and_rewritten_books_are_seen() {
        walk()
        File(root, "Download/d.epub").delete()
        val a = epub("Books/a.epub", "longer content").apply { setLastModified(System.currentTimeMillis() - 5_000) }
        val found = walk()
        assertEquals(listOf("Books/a.epub"), found.map { rel(it.path) })
        assertEquals(a.length(), found.single().size); assertEquals(a.lastModified(), found.single().mtimeMs)
    }

    @Test fun recently_changed_dir_is_listed_again() {
        future = 0 // the dirs were changed just now: their mtime cannot be trusted yet (a change in the same tick)
        walk()
        walk()
        assertTrue("Books" in listed.map(::rel), "$listed")
    }
}
