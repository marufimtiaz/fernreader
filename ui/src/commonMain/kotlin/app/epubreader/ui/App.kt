package app.epubreader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.epubreader.core.data.SourceRef
import app.epubreader.home.HomeScreen
import app.epubreader.home.HomeState
import app.epubreader.reader.EventProbe
import app.epubreader.reader.ReaderController
import app.epubreader.reader.ReaderScreen
import app.epubreader.ui.theme.AppTheme

/**
 * Root. [readerSurface] (the WebView) is drawn first and stays underneath; the home screen is opaque over it,
 * the reader route draws chrome only. [webViewTooOld] (no usable WebView): a full-screen message instead.
 */
@Composable
fun App(
    dark: Boolean,
    webViewTooOld: Boolean,
    nav: Navigator,
    home: HomeState,
    reader: ReaderController,
    probe: EventProbe,
    readerSurface: @Composable () -> Unit,
    onOpenPicker: () -> Unit,
    onOpen: (SourceRef) -> Unit,
    onBack: () -> Unit,
) = AppTheme(dark) {
    if (webViewTooOld) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(Copy.webViewTooOld) }
        }
        return@AppTheme
    }
    Box(Modifier.fillMaxSize()) {
        readerSurface()
        when (nav.top) {
            Route.Home -> HomeScreen(home, onOpenPicker = onOpenPicker, onOpen = onOpen, probe = probe)
            Route.Reader -> ReaderScreen(reader, onBack = onBack, onForget = home::forget, probe = probe)
        }
    }
}
