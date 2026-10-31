package app.epubreader.reader.toc

import androidx.compose.runtime.Composable
import app.epubreader.reader.EventProbe
import app.epubreader.reader.bridge.TocItem

/** Stub until Task 2.7 (table of contents panel). */
@Suppress("UNUSED_PARAMETER")
@Composable
fun TocPanel(items: List<TocItem>, currentHref: String?, onSelect: (TocItem) -> Unit, onDismiss: () -> Unit, probe: EventProbe) {
}
