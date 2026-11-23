package app.fernreader.devtools

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * Dev-only: the shared UI in a phone-sized window over fake data (docs/DEVELOPMENT.md). Esc is the system Back.
 * `--screenshots <dir> [licenses.md]` renders the screens to PNGs instead, without a window.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--screenshots") {
        val out = File(args.getOrNull(1) ?: "screenshots")
        val graph = DevGraph(args.getOrNull(2)?.let(::File))
        runBlocking { graph.seed(); renderScreens(graph, out) }
        exitProcess(0)
    }
    val graph = DevGraph(File("docs/licenses.md").takeIf { it.exists() } ?: File("../../docs/licenses.md"))
    runBlocking { graph.seed() }
    application {
        Window(
            title = "FernReader (dev)", onCloseRequest = ::exitApplication, state = rememberWindowState(size = DpSize(412.dp, 915.dp)),
            onKeyEvent = { e -> (e.type == KeyEventType.KeyDown && e.key == Key.Escape).also { if (it) goBack(graph) } },
        ) { DevApp(graph) }
    }
}
