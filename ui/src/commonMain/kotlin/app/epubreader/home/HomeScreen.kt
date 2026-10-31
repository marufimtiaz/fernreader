package app.epubreader.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.epubreader.core.data.SourceRef
import app.epubreader.reader.EventProbe
import app.epubreader.ui.Copy

/** M2 home: "Open book" + recent books. Opaque (Material Surface also blocks touches to the WebView below). */
@Composable
fun HomeScreen(state: HomeState, onOpenPicker: () -> Unit, onOpen: (SourceRef) -> Unit, probe: EventProbe) {
    val books by state.recent.collectAsState()
    val logged = remember { booleanArrayOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Copy.appTitle, style = MaterialTheme.typography.headlineSmall)
                Button(onClick = onOpenPicker) { Text(Copy.openBook) }
            }
            if (books.isEmpty()) Text(Copy.recentEmpty, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            LazyColumn {
                itemsIndexed(books, key = { _, b -> b.id }) { i, b ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onOpen(b.source) }.padding(horizontal = 16.dp, vertical = 12.dp)
                            .let { m -> if (i != 0) m else m.onGloballyPositioned { c ->
                                if (!logged[0]) { logged[0] = true; probe.ev("row0", "y=${c.boundsInWindow().center.y.toInt()}") }
                            } },
                    ) {
                        Text(b.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (b.authors.isNotEmpty()) Text(b.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
