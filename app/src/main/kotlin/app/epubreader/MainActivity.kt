package app.epubreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.epubreader.bench.Probe
import app.epubreader.reader.ReaderEngine
import app.epubreader.ui.App

class MainActivity : ComponentActivity() {
    lateinit var reader: ReaderEngine
        private set
    private var webViewTooOld by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = ReaderEngine(this)
        setContent { App(dark = isSystemInDarkTheme(), webViewTooOld = webViewTooOld) }
        // Pre-warm after the first frame so it never delays the library (P-001).
        window.decorView.post {
            Probe.ev("first_frame")
            window.decorView.post {
                // WEBVIEW_TOO_OLD (or no WebView at all): show a full-screen message instead of crashing.
                runCatching { reader.prewarm() }.onFailure { webViewTooOld = true }
            }
        }
    }

    override fun onDestroy() {
        reader.destroy()
        super.onDestroy()
    }
}
