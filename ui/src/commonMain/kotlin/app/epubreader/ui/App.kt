package app.epubreader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.epubreader.ui.theme.AppTheme

/** Root composable. M1: placeholder home; the home screen and the reader route arrive in M2. */
@Composable
fun App(dark: Boolean, webViewTooOld: Boolean = false) = AppTheme(dark) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (webViewTooOld) Copy.webViewTooOld else Copy.emptyLibrary)
        }
    }
}

/** User-facing strings (our own wording). */
object Copy {
    const val emptyLibrary = "Your library is empty"
    const val webViewTooOld = "This device's web engine is too old to display books. Update \"Android System WebView\" in the app store, then reopen this app."
}
