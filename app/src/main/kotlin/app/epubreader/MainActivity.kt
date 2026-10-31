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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.epubreader.bench.BenchCommands
import app.epubreader.bench.BenchMarker
import app.epubreader.bench.Probe
import app.epubreader.core.data.SourceRef
import app.epubreader.home.HomeState
import app.epubreader.library.StorageAccess
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

class MainActivity : ComponentActivity() {
    lateinit var reader: ReaderEngine
        private set
    lateinit var controller: ReaderController
        private set
    lateinit var nav: Navigator
        private set
    private val access = StorageAccess(this) { onAccessMaybeGranted() }
    /** Task 3.6 hooks the first full scan here. */
    var onAccessGranted: () -> Unit = {}
    private val probe = EventProbe { n, x -> Probe.ev(n, x) }
    private var webViewTooOld by mutableStateOf(false)

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        // Keep access across restarts for the recent list; some providers do not allow it (then: this session only).
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        openBook(SourceRef.Content(uri.toString()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // status/navigation bar icons follow the system light/dark setting, like the app theme
        super.onCreate(savedInstanceState)
        val graph = (application as EpubReaderApp).graph
        // Decided before the first frame, so neither screen flashes (F-001).
        nav = Navigator(if (access.granted()) Route.Library else Route.Onboarding)
        reader = ReaderEngine(this, graph.server)
        controller = ReaderController(
            reader, graph.opener, graph.library, graph.positions, lifecycleScope, WebTexts(Copy.missingPart), probe,
            closeScope = graph.appScope, // onDestroy's close-out must not run on the already-cancelled lifecycleScope
        )
        val home = HomeState(graph.library, lifecycleScope)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goBack()) { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        setContent {
            Box {
                App(
                    dark = isSystemInDarkTheme(), webViewTooOld = webViewTooOld, nav = nav, home = home, reader = controller, probe = probe,
                    readerSurface = { ReaderSurface(reader, covered = nav.top != Route.Reader) },
                    onOpenPicker = { pick.launch(arrayOf("application/epub+zip", "application/octet-stream")) },
                    onGrantAccess = access::request, onLeave = ::finish,
                    onOpen = ::openBook, onBack = { goBack() },
                )
                if (Probe.enabled) BenchMarker.Draw()
            }
        }
        if (Probe.enabled) {
            BenchCommands.register("openPath") { openBook(SourceRef.File(it)) }
            BenchCommands.register("back") { goBack() }
            BenchCommands.register("next") { reader.send(ToWeb.Next()) }
            BenchCommands.register("prev") { reader.send(ToWeb.Prev()) }
            BenchCommands.register("toc") { controller.showToc() }
            BenchCommands.register("tocJump") { label ->
                controller.state.value.toc.firstOrNull { it.label.contains(label, ignoreCase = true) }?.let(controller::onTocSelected)
            }
        }
        // Pre-warm after the first frame so it never delays the home screen (P-001).
        window.decorView.post {
            Probe.ev("first_frame")
            window.decorView.post {
                // WEBVIEW_TOO_OLD (or no WebView at all): show a full-screen message instead of crashing (R16).
                runCatching { reader.prewarm() }.onFailure { webViewTooOld = true }
            }
        }
    }

    fun openBook(ref: SourceRef) {
        if (webViewTooOld) return
        Probe.ev("tap_book", ref.key)
        controller.open(ref)
        if (nav.top != Route.Reader) nav.push(Route.Reader)
    }

    /** Returns false when Back should leave the app. Leaving the reader never waits for the engine (R-005). */
    fun goBack(): Boolean = when (nav.top) {
        Route.Reader -> { if (!controller.backInside()) { controller.close(); nav.pop(); Probe.ev("home_shown") }; true }
        Route.Library, Route.Onboarding -> false
    }

    override fun onResume() {
        super.onResume()
        onAccessMaybeGranted()
    }

    /** Back from the system permission screen (or the API 26–29 dialog): with access, the library opens at once. */
    private fun onAccessMaybeGranted() {
        if (nav.top != Route.Onboarding || !access.granted()) return
        nav.replaceRoot(Route.Library)
        onAccessGranted()
    }

    override fun onDestroy() {
        controller.close()
        reader.destroy()
        super.onDestroy()
    }
}
