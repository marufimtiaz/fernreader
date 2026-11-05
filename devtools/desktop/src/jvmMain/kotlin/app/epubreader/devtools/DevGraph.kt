package app.epubreader.devtools

import app.epubreader.core.epub.meta.BookIdentity
import app.epubreader.core.data.HybridClock
import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.OpenedBookInfo
import app.epubreader.core.data.PositionWriter
import app.epubreader.core.data.ScanFailureEntity
import app.epubreader.core.data.ScannedBook
import app.epubreader.core.data.SettingsStore
import app.epubreader.core.data.SourceRef
import app.epubreader.core.data.buildDatabase
import app.epubreader.core.data.jvmDatabaseBuilder
import app.epubreader.core.library.ScanState
import app.epubreader.library.LibraryState
import app.epubreader.reader.BookOpenerPort
import app.epubreader.reader.OpenResult
import app.epubreader.reader.ReaderController
import app.epubreader.reader.ReaderPort
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import app.epubreader.reader.bridge.TocItem
import app.epubreader.reader.bridge.WebTexts
import app.epubreader.settings.AppPrefs
import app.epubreader.settings.SettingsState
import app.epubreader.ui.Copy
import app.epubreader.ui.Navigator
import app.epubreader.ui.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.nio.file.Files

private const val DIGEST = "dev"
private const val ROOT = "/storage/emulated/0"

private data class Sample(
    val id: String, val title: String, val authors: List<String>, val folder: String, val readDaysAgo: Long? = null,
    val direction: String = "auto", val collection: String? = null, val hidden: Boolean = false,
) {
    val path get() = "$ROOT/$folder/$id.epub"
}

/** Public-domain titles, with what the screens have to cope with: no author, a long title, right-to-left, folders, unread. */
private val SAMPLES = listOf(
    Sample("moby", "Moby-Dick; or, The Whale", listOf("Herman Melville"), "Books/Classics", readDaysAgo = 1, collection = "Sea stories"),
    Sample("alice", "Alice's Adventures in Wonderland", listOf("Lewis Carroll"), "Books/Classics", readDaysAgo = 3),
    Sample("frank", "Frankenstein; or, The Modern Prometheus", listOf("Mary Shelley"), "Books/Classics", collection = "To read"),
    Sample("war", "War and Peace", listOf("Leo Tolstoy", "Louise Maude", "Aylmer Maude"), "Books/Russian", collection = "To read"),
    Sample("pride", "Pride and Prejudice", listOf("Jane Austen"), "Books/Classics", readDaysAgo = 12),
    Sample("expect", "Great Expectations", listOf("Charles Dickens"), "Download"),
    Sample("shakes", "The Complete Works of William Shakespeare, with a Title Long Enough to Need Truncating Twice", listOf("William Shakespeare"), "Documents"),
    Sample("anon", "The Anonymous Pamphlet", emptyList(), "Download"),
    Sample("he", "ספר הדקדוק", listOf("מחבר"), "Documents/עברית", direction = "rtl"),
    Sample("ar", "ألف ليلة وليلة", emptyList(), "Documents", direction = "rtl"),
    Sample("kusa", "草枕", listOf("夏目 漱石"), "Books/Japanese", readDaysAgo = 30),
    Sample("voyage", "The Voyage of Life", listOf("Thomas Cole"), "Download", hidden = true),
)

/** Everything the app's AppGraph and MainActivity wire up, over a temporary Room database and without Android. */
class DevGraph(val licencesFile: java.io.File?) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dir = Files.createTempDirectory("fernreader-dev").toFile().apply { deleteOnExit() }
    private val db = buildDatabase(jvmDatabaseBuilder(dir.resolve("library.db").path))
    private val clock = HybridClock(System::currentTimeMillis)
    val library = LibraryRepository(db, clock, deleteFile = { true })
    private val settingsStore = SettingsStore(db, clock)
    val prefs = AppPrefs(settingsStore, scope)
    val nav = Navigator(Route.Library)
    val scan = MutableStateFlow(ScanState())

    val libraryState = LibraryState(
        library.library(), library.failures(), scan, settingsStore, library::removeBook, scope, ops = library, folderView = prefs.folderView,
    )
    val settingsState = SettingsState(settingsStore, library.library(), library.hiddenBooks(), library::hide, library::unhide, rescan = {}, scope = scope)
    private val fakeReader = FakeReader(scope)
    val reader = ReaderController(
        fakeReader, FakeOpener, library, scope.async(Dispatchers.IO) { PositionWriter.create(db, clock, scope) }, scope,
        WebTexts(Copy.missingPart), settingsStore = settingsStore,
    )

    /** A tap on the page's centre: the reader's bars come and go. */
    fun tapPage() = fakeReader.tap()

    /** Fills the library; returns when the books are there. */
    suspend fun seed() {
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        val dao = db.libraryDao()
        val collections = SAMPLES.mapNotNull { it.collection }.distinct().associateWith { library.createCollection(it) }
        SAMPLES.forEachIndexed { i, s ->
            val key = BookIdentity.of(s.id, DIGEST)
            val folder = s.path.substringBeforeLast('/')
            val id = dao.recordScanned(
                ScannedBook(key, s.title, s.title.lowercase().removePrefix("the "), s.authors.joinToString("\n"), s.authors.firstOrNull()?.lowercase().orEmpty(),
                    "en", s.direction, s.path, folder, size = 500_000L + i * 120_000L, mtimeMs = now - i * day), scanId = 1, now = now - i * day,
            )
            s.collection?.let { library.addToCollection(collections.getValue(it), listOf(id)) }
            if (s.hidden) library.hide(listOf(id))
            if (s.readDaysAgo != null) dao.touchOpened(id, now - s.readDaysAgo * day)
        }
        dao.upsertFailure(ScanFailureEntity("$ROOT/Download/broken.epub", "$ROOT/Download", "broken.epub", "NOT_A_ZIP", 2_500, now, 1))
        library.library().first { it.isNotEmpty() }
    }
}

private object FakeOpener : BookOpenerPort {
    override suspend fun open(ref: SourceRef): OpenResult = OpenResult.Ok(sessionId = ref.key, cdDigest = DIGEST, size = 1_000_000)
    override suspend fun close(sessionId: String) {}
}

/** Answers like the reader page does for a short book of five sections, so the reader's bars and panels have a book. */
private class FakeReader(private val scope: CoroutineScope) : ReaderPort {
    override val events = MutableSharedFlow<FromWeb>(extraBufferCapacity = 16)
    override val isReady = MutableStateFlow(true)
    private var at = 0
    private var session: String? = null

    fun tap() { session?.let { s -> scope.launch { events.emit(FromWeb.Tap(sessionId = s, x = 0.5, y = 0.5)) } } }

    override fun send(m: ToWeb) {
        when (m) {
            is ToWeb.Open -> scope.launch {
                val title = m.sessionId.substringAfterLast('/').removeSuffix(".epub")
                val toc = List(SECTIONS) { TocItem("Chapter ${it + 1}", "c$it.xhtml", depth = 0, index = it) }
                at = 0
                session = m.sessionId
                events.emit(FromWeb.Opened(sessionId = m.sessionId, identifier = title, title = title, authors = emptyList(), dir = "ltr",
                    fixedLayout = false, sections = SECTIONS, toc = toc, sizes = List(SECTIONS) { 1_000L }))
                moved(m.sessionId)
                events.emit(FromWeb.Painted(sessionId = m.sessionId))
            }
            is ToWeb.Next -> turn(1)
            is ToWeb.Prev -> turn(-1)
            is ToWeb.Close -> session = null
            else -> Unit
        }
    }

    private fun turn(by: Int) {
        val s = session ?: return
        at = (at + by).coerceIn(0, SECTIONS - 1)
        scope.launch { moved(s) }
    }

    private suspend fun moved(sessionId: String) =
        events.emit(FromWeb.Relocate(sessionId = sessionId, cfi = "epubcfi(/6/${at * 2 + 2})", fraction = at / SECTIONS.toDouble(), sectionIndex = at, tocHref = "c$at.xhtml", page = 1, pages = 3))

    private companion object { const val SECTIONS = 5 }
}
