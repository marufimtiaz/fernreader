package app.fernreader.devtools

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.fernreader.core.data.ReaderSheetTab
import app.fernreader.core.data.SourceRef
import app.fernreader.library.LibraryLayout
import app.fernreader.library.LibraryView
import app.fernreader.settings.AppThemeMode
import app.fernreader.ui.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

private const val WIDTH = 412
private const val HEIGHT = 915
private const val SCALE = 2f

/** One shot per screen: the state changes on the graph, the scene draws what the app would. */
suspend fun renderScreens(g: DevGraph, dir: File) {
    dir.mkdirs()
    val scene = ImageComposeScene(width = (WIDTH * SCALE).toInt(), height = (HEIGHT * SCALE).toInt(), density = Density(SCALE)) { DevApp(g) }
    var clock = 0L
    suspend fun shot(name: String) {
        repeat(SETTLE_FRAMES) { // the screens read flows from the database and animate in; frames advance the scene's clock
            scene.render(clock)
            clock += FRAME_NANOS
            delay(FRAME_MS)
        }
        val png = scene.render(clock).encodeToData(EncodedImageFormat.PNG) ?: error("no PNG for $name")
        dir.resolve("$name.png").writeBytes(png.bytes)
        println("rendered $name")
    }
    g.prefs.setTheme(AppThemeMode.LIGHT)
    shot("library-grid")
    g.libraryState.setLayout(LibraryLayout.LIST)
    shot("library-list")
    g.libraryState.setDrawer(true)
    shot("drawer")
    g.libraryState.setDrawer(false)
    g.libraryState.openView(LibraryView.Folders)
    shot("library-folders")
    g.libraryState.openView(LibraryView.All)
    g.libraryState.setLayout(LibraryLayout.GRID)
    g.nav.push(Route.Settings)
    shot("settings")
    g.nav.push(Route.HiddenBooks)
    shot("hidden-books")
    g.nav.pop()
    g.nav.push(Route.About)
    shot("about")
    g.nav.push(Route.Licences)
    shot("licences")
    g.nav.pop(); g.nav.pop(); g.nav.pop()
    g.nav.push(Route.BookDetails(g.library.library().first().first().id))
    shot("book-details")
    g.nav.pop()
    g.prefs.setTheme(AppThemeMode.DARK)
    shot("library-dark")
    g.prefs.setTheme(AppThemeMode.LIGHT)
    val moby = g.library.library().first().first { it.title.startsWith("Moby") }
    g.nav.push(Route.Reader)
    g.reader.open(SourceRef.fromKey(moby.path))
    shot("reader-placeholder")
    g.tapPage()
    shot("reader-controls")
    g.reader.showSheet(ReaderSheetTab.DISPLAY)
    shot("reader-sheet-display")
    g.reader.selectSheetTab(ReaderSheetTab.GENERAL)
    shot("reader-sheet-general")
    g.reader.hideSheet()
    val (book, path) = g.addIdenticalFile(moby.title)
    g.nav.push(Route.BookDetails(book))
    shot("book-details-two-files")
    g.nav.pop()
    g.nav.push(Route.BookDetails(g.library.keepSeparate(path)!!))
    shot("book-details-separate-copy")
    scene.close()
}

private const val SETTLE_FRAMES = 40
private const val FRAME_MS = 25L
private const val FRAME_NANOS = 16_666_667L
