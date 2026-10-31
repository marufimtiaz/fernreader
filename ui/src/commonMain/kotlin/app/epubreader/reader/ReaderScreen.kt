package app.epubreader.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import app.epubreader.reader.display.DisplayPanel
import app.epubreader.reader.display.ReadingTheme
import app.epubreader.reader.search.SearchPanel
import app.epubreader.reader.toc.TocPanel
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.epubreader.ui.Copy

/**
 * Reader chrome drawn over the WebView (which lies underneath, full size). Nothing here reflows the page.
 * While loading or on error the screen is opaque (P-015: frame + progress at once). Icons and the full control
 * set come with M6 Task 6.1; M2 uses text buttons.
 */
@Composable
fun ReaderScreen(c: ReaderController, onBack: () -> Unit, onForget: (SourceRef) -> Unit, probe: EventProbe, fonts: Map<String, FontFamily> = emptyMap()) {
    val s by c.state.collectAsState()
    val settings by c.settings.collectAsState()
    val page by c.page.collectAsState()
    LaunchedEffect(Unit) { withFrameNanos { }; probe.ev("reader_frame", "") }
    // F-074: the reader's own surfaces (loading screen, bars, panels) follow the reading theme, not the app theme.
    MaterialTheme(colorScheme = ReadingTheme.of(settings.theme).colorScheme()) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        LaunchedEffect(landscape) { c.setLandscape(landscape) } // C-004: AUTO flow follows the orientation
        when {
            s.error != null -> Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    val err = s.error
                    Text(when (err) { is ReaderError.Open -> Copy.openFailure(err.reason); ReaderError.FileChanged -> Copy.fileChanged; else -> Copy.engineError }, style = MaterialTheme.typography.bodyLarge)
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
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
        if (s.chromeVisible && s.error == null) {
            Surface(Modifier.fillMaxWidth().align(Alignment.TopStart), tonalElevation = 3.dp) {
                Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text(Copy.back) }
                    Text(s.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = c::showSearch) { Text(Copy.search) }
                    TextButton(onClick = c::showDisplay) { Text(Copy.display) }
                    TextButton(onClick = c::showToc, enabled = s.toc.isNotEmpty()) { Text(Copy.contents) }
                }
            }
        }
        // F-034: always visible, in the page's bottom margin (32 px). Paged mode keeps that margin free of text; in scroll
        // mode text scrolls through it, so the label sits on a strip of page colour and the text passes under it.
        if (!s.loading && s.error == null) Copy.pageLabel(page)?.let { label ->
            Box(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().fillMaxWidth().height(32.dp)
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
            }
        }
        if (s.chromeVisible && s.error == null) {
            var drag by remember { mutableStateOf<Float?>(null) }
            Surface(Modifier.fillMaxWidth().align(Alignment.BottomCenter), tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(Copy.pageLabel(page) ?: "", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = drag ?: s.fraction.toFloat(), onValueChange = { drag = it },
                        onValueChangeFinished = { drag?.let { c.seek(it.toDouble()) }; drag = null },
                        modifier = Modifier.semantics { contentDescription = Copy.seek },
                    )
                }
            }
        }
        if (s.displayVisible && s.error == null) {
            LaunchedEffect(Unit) { withFrameNanos { }; probe.ev("display_shown", "") }
            DisplayPanel(settings, fonts, onChange = c::updateSettings, modifier = Modifier.align(Alignment.BottomCenter))
        }
        if (s.tocVisible) TocPanel(s.toc, s.currentTocHref, onSelect = c::onTocSelected, onDismiss = c::hideToc, probe = probe)
        if (s.searchVisible && s.error == null) SearchPanel(c.searcher, onDismiss = c::hideSearch)
        s.notice?.let {
            LaunchedEffect(it) { kotlinx.coroutines.delay(3_000); c.dismissNotice() }
            Snackbar(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) { Text(if (it == Notice.HitNotFound) Copy.hitNotFound else Copy.missingTarget) }
        }
    }
    }
}
