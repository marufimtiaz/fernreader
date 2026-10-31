package app.epubreader.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.epubreader.core.data.SourceRef
import app.epubreader.reader.EventProbe
import app.epubreader.ui.Copy

/** Stub until Task 2.6: opaque (covers the reader WebView) with only the "Open book" action. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun HomeScreen(home: HomeState, onOpenPicker: () -> Unit, onOpen: (SourceRef) -> Unit, probe: EventProbe) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Copy.recentEmpty)
            Button(onClick = onOpenPicker) { Text(Copy.openBook) }
        }
    }
}
