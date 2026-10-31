package app.epubreader.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.epubreader.core.data.SourceRef
import app.epubreader.reader.toc.TocPanel
import app.epubreader.ui.Copy

/**
 * Reader chrome drawn over the WebView (which lies underneath, full size). Nothing here reflows the page.
 * While loading or on error the screen is opaque (P-015: frame + progress at once). Icons and the full control
 * set come with M6 Task 6.1; M2 uses text buttons.
 */
@Composable
fun ReaderScreen(c: ReaderController, onBack: () -> Unit, onForget: (SourceRef) -> Unit, probe: EventProbe) {
    val s by c.state.collectAsState()
    LaunchedEffect(Unit) { withFrameNanos { }; probe.ev("reader_frame", "") }
    Box(Modifier.fillMaxSize()) {
        when {
            s.error != null -> Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.Center) {
                    val err = s.error
                    Text(if (err is ReaderError.Open) Copy.openFailure(err.reason) else Copy.engineError, style = MaterialTheme.typography.bodyLarge)
                    if (err is ReaderError.Engine) Text(err.message, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = onBack) { Text(Copy.back) }
                        if (err is ReaderError.Open && err.reason == OpenFailure.NO_ACCESS) s.source?.let { src ->
                            TextButton(onClick = { onForget(src); onBack() }) { Text(Copy.removeFromRecent) }
                        }
                    }
                }
            }
            s.loading -> Surface(Modifier.fillMaxSize()) {
                Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
        if (s.chromeVisible && s.error == null) {
            Surface(Modifier.fillMaxWidth().align(Alignment.TopStart), tonalElevation = 3.dp) {
                Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text(Copy.back) }
                    Text(s.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = c::showToc, enabled = s.toc.isNotEmpty()) { Text(Copy.contents) }
                }
            }
        }
        if (s.tocVisible) TocPanel(s.toc, s.currentTocHref, onSelect = c::onTocSelected, onDismiss = c::hideToc, probe = probe)
        s.notice?.let {
            LaunchedEffect(it) { kotlinx.coroutines.delay(3_000); c.dismissNotice() }
            Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp)) { Text(Copy.missingTarget) }
        }
    }
}
