package app.epubreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.epubreader.reader.ReaderWindowState
import app.epubreader.reader.readerWindowState
import android.view.WindowManager
import app.epubreader.reader.display.ReadingTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.epubreader.bench.BenchCommands
import app.epubreader.bench.BenchMarker
import app.epubreader.bench.Probe
import app.epubreader.core.data.SourceRef
import app.epubreader.library.LibraryState
import app.epubreader.library.releaseCoverMemory
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.android.awaitFrame
import app.epubreader.library.FileWatch
import app.epubreader.library.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.epubreader.reader.EventProbe
import app.epubreader.reader.ReaderController
import app.epubreader.reader.ReaderEngine
import app.epubreader.reader.ReaderSurface
import app.epubreader.reader.bridge.ToWeb
import app.epubreader.reader.bridge.WebTexts
import app.epubreader.ui.App
import app.epubreader.ui.Copy
import app.epubreader.ui.Navigator
import app.epubreader.ui.Route

/** A backup of thousands of books is a few MB; anything far larger is not one of ours. */
private const val MAX_BACKUP_BYTES = 16 shl 20

class MainActivity : ComponentActivity() {
    lateinit var reader: ReaderEngine
        private set
    lateinit var controller: ReaderController
        private set
    lateinit var nav: Navigator
        private set
    private lateinit var library: LibraryState
    private lateinit var settingsState: app.epubreader.settings.SettingsState
    private lateinit var backupState: app.epubreader.settings.BackupState
    private val access = StorageAccess(this) { onAccessMaybeGranted() }
    /** Access was just granted: the first full scan starts (F-003). Tests replace it. */
    var onAccessGranted: () -> Unit = {}
    private var resumedOnce = false
    /**
     * How the open book came from another app (F-013). One activity instance only (singleTask: one reader engine, one
     * opener). COLD: this activity was started for it, so Back finishes it. WARM: the app was already running, so Back
     * returns to the library and moves the app to the back, revealing the calling app.
     */
    private enum class View { NONE, COLD, WARM }
    private var view = View.NONE
    private var watch: FileWatch? = null
    private val probe = EventProbe { n, x -> Probe.ev(n, x) }
    private var webViewTooOld by mutableStateOf(false)

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        // Keep access across restarts for the recent list; some providers do not allow it (then: this session only).
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        openBook(SourceRef.Content(uri.toString()))
    }

    /** F-090: "Back up now" → system save dialog with a dated default name. */
    private val saveBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val graph = (application as EpubReaderApp).graph
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = graph.backup.export(BuildConfig.VERSION_NAME)
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                }.isSuccess
            }
            if (ok) backupState.saved(displayName(uri) ?: backupFileName()) else backupState.saveFailed()
        }
    }

    /** F-090: "Restore" → pick a backup file. */
    private val pickBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) offerRestore(uri) }

    /** F-011: the discovery folder, chosen with the system folder picker. */
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val path = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?.takeIf { uri.authority == "com.android.externalstorage.documents" }
            ?.let(app.epubreader.settings.FolderPaths::fromTreeDocumentId)
        if (path == null) android.widget.Toast.makeText(this, Copy.folderNotOnDevice, android.widget.Toast.LENGTH_LONG).show()
        else settingsState.chooseFolder(path)
    }

    private fun backupFileName(): String {
        val c = java.util.Calendar.getInstance()
        return app.epubreader.core.data.Backup.fileName(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /** Reads a backup file (capped: a backup is small; anything huge is not ours) and offers to restore it. */
    private fun offerRestore(uri: Uri) {
        lifecycleScope.launch {
            val text: String? = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)!!.use { s ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 shl 10)
                        while (true) {
                            val n = s.read(buf); if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_BACKUP_BYTES) return@use null
                        }
                        out.toByteArray().decodeToString()
                    }
                }.getOrNull()
            }
            if (text == null) backupState.readFailed() else backupState.offer(text)
        }
    }

    private fun formatDate(ms: Long): String = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(ms))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // status/navigation bar icons follow the system light/dark setting, like the app theme
        super.onCreate(savedInstanceState)
        val graph = (application as EpubReaderApp).graph
        onAccessGranted = graph.scans::onAccessGranted
        // Decided before the first frame, so neither screen flashes (F-001).
        nav = Navigator(if (access.granted()) Route.Library else Route.Onboarding)
        reader = ReaderEngine(this, graph.server)
        controller = ReaderController(
            reader, graph.opener, graph.library, graph.positions, lifecycleScope, WebTexts(Copy.missingPart), probe,
            settingsStore = graph.settings,
            search = graph.bookSearch, searchHistory = graph.searchHistory,
            closeScope = graph.appScope, // onDestroy's close-out must not run on the already-cancelled lifecycleScope
        )
        val library = LibraryState(graph.library.libraryLive(graph.scans.state.map { it.found }), graph.library.failures(), graph.scans.state, graph.settings, graph.removal::remove, lifecycleScope,
            exists = { withContext(Dispatchers.IO) { java.io.File(it).exists() } }, ops = graph.library, folderView = graph.prefs.folderView)
        this.library = library
        settingsState = app.epubreader.settings.SettingsState(
            graph.settings, graph.library.library(), graph.library.hiddenBooks(), hide = graph.library::hide, unhide = graph.library::unhide,
            rescan = graph.scans::rescan, scope = lifecycleScope,
        )
        backupState = app.epubreader.settings.BackupState(
            parse = app.epubreader.core.data.Backup::parse, restore = { f -> graph.backup.restore(f) }, scope = lifecycleScope,
            onRestored = {
                // Settings held in memory follow the restored values at once (F-100: no restart).
                val rs = app.epubreader.core.data.ReaderSettings.decode(withContext(Dispatchers.IO) { graph.settings.get(app.epubreader.core.data.ReaderSettings.KEY) })
                controller.updateSettings { rs }
                graph.prefs.reload(); library.reloadPrefs(); settingsState.reload()
                graph.scans.rescan()
            },
        )
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goBack()) { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        setContent {
            val rs by controller.settings.collectAsState()
            val theme = ReadingTheme.of(rs.theme)
            val mode by graph.prefs.theme.collectAsState()
            val dark = when (mode) {
                app.epubreader.settings.AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                app.epubreader.settings.AppThemeMode.LIGHT -> false
                app.epubreader.settings.AppThemeMode.DARK -> true
            }
            androidx.compose.runtime.LaunchedEffect(mode) { withContext(Dispatchers.IO) { app.epubreader.settings.AppThemeCache.write(this@MainActivity, mode) } }
            val inReader = nav.top == Route.Reader
            val ui by controller.state.collectAsState()
            val win = readerWindowState(inReader, ui.open, ui.chromeVisible, rs)
            SideEffect { applyReaderWindow(inReader, rs.brightness, lightBars = if (inReader) theme.isLight else !dark, win) }
            Box {
                App(
                    dark = dark, webViewTooOld = webViewTooOld, nav = nav, library = library, reader = controller, probe = probe,
                    readerSurface = { ReaderSurface(reader, covered = nav.top != Route.Reader, background = theme.background, fullscreen = rs.fullscreen) },
                    fonts = fonts,
                    onOpenPicker = { pick.launch(arrayOf("application/epub+zip", "application/octet-stream")) },
                    onGrantAccess = access::request, onLeave = ::finish,
                    onOpen = ::openBook, onBack = { goBack() }, onRescan = graph.scans::rescan,
                    onForget = { ref -> graph.appScope.launch { graph.library.forget(ref) } },
                    pages = { route ->
                        AppPages(route, nav, graph, settingsState, controller, pageActions, BuildConfig.VERSION_NAME,
                            readAsset = { name -> assets.open(name).use { it.readBytes().decodeToString() } })
                    },
                    overlay = {
                        val b by backupState.ui.collectAsState()
                        app.epubreader.settings.BackupDialogs(b, onConfirm = backupState::confirm, onDismiss = backupState::dismiss, date = ::formatDate)
                    },
                )
                if (Probe.enabled) BenchMarker.Draw()
            }
        }
        if (savedInstanceState == null) openFromIntent(intent, cold = true)
        if (Probe.enabled) {
            BenchCommands.register("openPath") { openBook(SourceRef.File(it)) }
            BenchCommands.register("back") { goBack() }
            BenchCommands.register("rescan") { graph.scans.rescan() }
            BenchCommands.register("next") { reader.send(ToWeb.Next()) }
            BenchCommands.register("prev") { reader.send(ToWeb.Prev()) }
            BenchCommands.register("toc") { controller.showToc() }
            // M4: display settings, rotation, chapter ends (bench/groups/action.sh, pagetotal.sh)
            BenchCommands.register("fontStep") { d -> controller.updateSettings { it.stepFont(d.trim().toIntOrNull() ?: 1) } }
            BenchCommands.register("fontScale") { v -> v.trim().toDoubleOrNull()?.let { f -> controller.updateSettings { it.copy(fontScale = f) } } }
            BenchCommands.register("resetSettings") { controller.updateSettings { app.epubreader.core.data.ReaderSettings() } }
            BenchCommands.register("display") { controller.showDisplay() }
            BenchCommands.register("hideDisplay") { controller.hideDisplay() }
            BenchCommands.register("lastPage") { reader.webView.evaluateJavascript("window.__readerTest.lastPage()", null) }
            BenchCommands.register("orient") { o ->
                requestedOrientation = when (o.trim()) {
                    "land" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    "port" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
            // M5: in-book search (bench/groups/search.sh)
            BenchCommands.register("search") { controller.searchFor(it) }
            BenchCommands.register("searchWhole") { text ->
                if (!controller.searcher.state.value.wholeWord) controller.searcher.toggleWholeWord()
                controller.searchFor(text)
            }
            BenchCommands.register("hideSearch") { controller.hideSearch() }
            BenchCommands.register("tocJump") { label ->
                controller.state.value.toc.firstOrNull { it.label.contains(label, ignoreCase = true) }?.let(controller::onTocSelected)
            }
        }
        // Pre-warm after the first frame so it never delays the library (P-001). Creating the WebView blocks the main
        // thread for ~0.3-0.5 s, so on the library it waits until the books are on screen (or a first scan found
        // none, P-050 "first book visible"), at most 2 s. On the reader route (open-with) it starts at once.
        window.decorView.post {
            Probe.ev("first_frame")
            graph.scans.onAppStart() // incremental scan, after the first frame (P-001)
            lifecycleScope.launch {
                if (nav.top == Route.Library) withTimeoutOrNull(2_000) {
                    library.ui.first { it.loaded && (it.total > 0 || it.scan.lastComplete != null) }
                    library.shown.first { it || library.ui.value.total == 0 } // composed (LibraryScreen sets it)
                    awaitFrame(); awaitFrame() // and drawn
                }
                // WEBVIEW_TOO_OLD (or no WebView at all): show a full-screen message instead of crashing (R16).
                runCatching { reader.prewarm() }.onFailure { webViewTooOld = true }
            }
        }
    }

    private val pageActions by lazy {
        PageActions(
            pickFolder = { pickFolder.launch(null) },
            backUp = { saveBackup.launch(backupFileName()) },
            restore = { pickBackup.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) },
            date = ::formatDate,
        )
    }

    /** Bundled faces for the display panel's previews (F-071), from the same asset files the reader serves. */
    private val fonts by lazy {
        mapOf(
            "literata" to FontFamily(Font("reader/fonts/literata.ttf", assets)),
            "source-sans" to FontFamily(Font("reader/fonts/source-sans-3.ttf", assets)),
            "atkinson" to FontFamily(Font("reader/fonts/atkinson-regular.ttf", assets)),
        )
    }

    /**
     * Reader window state: app-controlled brightness only while reading (F-035 via F-075, "follow system" = none),
     * and bar icons that stay readable on the reading theme's strips.
     */
    private var appliedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private fun applyReaderWindow(inReader: Boolean, brightness: Double?, lightBars: Boolean, win: ReaderWindowState) {
        val want = if (inReader && brightness != null) brightness.toFloat() else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (window.attributes.screenBrightness != want) window.attributes = window.attributes.apply { screenBrightness = want }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (win.barsHidden) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
        if (win.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Only on a change, so the bench `orient` command keeps winning until the lock or the route changes.
        if (win.orientation != appliedOrientation) { appliedOrientation = win.orientation; requestedOrientation = win.orientation }
    }

    fun openBook(ref: SourceRef) {
        if (webViewTooOld) return
        Probe.ev("tap_book", ref.key)
        watch?.stop()
        watch = (ref as? SourceRef.File)?.let { FileWatch(it.path) { controller.fileGone() }.start() }
        controller.open(ref)
        (application as EpubReaderApp).graph.covers.setPaused(true)
        releaseCoverMemory(this)
        if (nav.top != Route.Reader) nav.push(Route.Reader)
    }

    /** F-013: an EPUB opened from another app (file manager, browser, mail). Back returns to that app. */
    private fun openFromIntent(intent: Intent?, cold: Boolean): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        // F-090: a backup file opened from a file manager offers restore; the app stays where it is.
        if (isBackupFile(intent, uri)) { offerRestore(uri); return true }
        val ref = when (uri.scheme) {
            "file" -> uri.path?.let { SourceRef.File(it) }
            "content" -> {
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } // often refused: fine
                SourceRef.Content(uri.toString())
            }
            else -> null
        } ?: return false
        view = if (cold) View.COLD else View.WARM
        openBook(ref)
        return true
    }

    private fun isBackupFile(intent: Intent, uri: Uri): Boolean {
        val type = intent.type ?: runCatching { contentResolver.getType(uri) }.getOrNull()
        val name = (uri.lastPathSegment ?: "").lowercase()
        return type == "application/json" || type == "text/json" || name.endsWith(".json")
    }

    /** Returns false when Back should leave the app. Leaving the reader never waits for the engine (R-005). */
    fun goBack(): Boolean = when (nav.top) {
        Route.Reader -> {
            if (!controller.backInside()) {
                watch?.stop(); watch = null
                controller.close()
                (application as EpubReaderApp).graph.covers.setPaused(false)
                when (view) {
                    View.COLD -> finish()
                    View.WARM -> { view = View.NONE; nav.pop(); moveTaskToBack(true) }
                    View.NONE -> { nav.pop(); Probe.ev("home_shown") }
                }
            }
            true
        }
        Route.Library -> library.back()
        Route.Onboarding -> false
        else -> nav.pop() // settings, about, hidden books, licences, details
    }

    /**
     * F-032/C-005 volume keys: on the reader route the controller decides (book open, no panel or sheet, setting not
     * OFF); its keys are consumed here, before the window's default handling, so the media volume does not change and
     * no volume UI shows. Down, up and auto-repeats are all consumed; only the first down turns a page.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val code = event.keyCode
        if ((code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || code == android.view.KeyEvent.KEYCODE_VOLUME_UP) && nav.top == Route.Reader &&
            controller.onVolumeKey(
                volumeDown = code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                press = event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0,
            )
        ) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Probe.ev("config_changed", "orientation=${newConfig.orientation}") // P-022 starts here
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent, cold = false)
    }

    override fun onResume() {
        super.onResume()
        onAccessMaybeGranted()
        // F-012: back to the library → incremental rescan (debounced). The first resume is the app start.
        if (resumedOnce && nav.top == Route.Library) (application as EpubReaderApp).graph.scans.onLibraryResumed()
        resumedOnce = true
    }

    /** Back from the system permission screen (or the API 26–29 dialog): with access, the library opens at once. */
    private fun onAccessMaybeGranted() {
        if (nav.top != Route.Onboarding || !access.granted()) return
        nav.replaceRoot(Route.Library)
        onAccessGranted()
    }

    override fun onDestroy() {
        watch?.stop()
        controller.close()
        reader.destroy()
        super.onDestroy()
    }
}
