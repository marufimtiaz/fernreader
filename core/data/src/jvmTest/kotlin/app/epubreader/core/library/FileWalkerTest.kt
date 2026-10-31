package app.epubreader.core.library

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
