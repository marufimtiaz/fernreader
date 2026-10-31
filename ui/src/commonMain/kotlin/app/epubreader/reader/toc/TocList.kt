package app.epubreader.reader.toc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.epubreader.reader.EventProbe
import app.epubreader.reader.bridge.TocItem
import app.epubreader.ui.Copy

/** The Contents tab's list. The tree is built when the tab opens (O(n), well inside P-031 for 2000 entries). */
@Composable
fun TocList(items: List<TocItem>, currentHref: String?, onSelect: (TocItem) -> Unit, probe: EventProbe) {
    val tree = remember(items) { TocTreeState(items) }
    val current = remember(tree) { tree.indexOfHref(currentHref)?.also(tree::reveal) }
    val rows = tree.visibleRows()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = current?.let { rows.indexOf(it).coerceAtLeast(0) } ?: 0)
    LaunchedEffect(Unit) { withFrameNanos { }; probe.ev("toc_shown", "") }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
        items(rows, key = { it }) { i ->
            val item = tree.items[i]
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(item) }.padding(start = (16 + 20 * item.depth).dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.label.ifBlank { Copy.untitledEntry }, Modifier.weight(1f).padding(vertical = 12.dp),
                    fontWeight = if (i == current) FontWeight.Bold else null,
                    color = if (item.missing) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                )
                if (tree.hasChildren(i)) TextButton(onClick = { tree.toggle(i) }, Modifier.width(56.dp)) {
                    Text(if (tree.isExpanded(i)) "−" else "+")
                }
            }
        }
    }
}
