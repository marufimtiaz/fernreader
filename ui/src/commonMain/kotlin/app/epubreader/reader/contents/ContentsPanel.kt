package app.epubreader.reader.contents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.epubreader.core.data.Bookmark
import app.epubreader.reader.ContentsTab
import app.epubreader.reader.EventProbe
import app.epubreader.reader.PageIndicator
import app.epubreader.reader.bridge.TocItem
import app.epubreader.reader.toc.TocList
import app.epubreader.ui.Copy

/**
 * Full-screen panel behind the reader's contents button (F-041, F-062): tabs Contents | Bookmarks | Highlights. The
 * Contents tab is the default and the only one composed until another is picked (the contents list builds its tree on
 * composition, P-031). [highlights] is the slot the annotations list fills in (Task 8); empty, it shows a short hint.
 */
@Composable
fun ContentsPanel(
    tab: ContentsTab, onTab: (ContentsTab) -> Unit,
    toc: List<TocItem>, currentHref: String?, onSelect: (TocItem) -> Unit,
    bookmarks: List<Bookmark>, indicator: PageIndicator, formatDate: (Long) -> String,
    onOpenBookmark: (Bookmark) -> Unit, onRemoveBookmark: (Bookmark) -> Unit,
    onDismiss: () -> Unit, probe: EventProbe,
    highlights: @Composable () -> Unit = { EmptyTab(Copy.noHighlights) },
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(Copy.close) }
            }
            TabRow(selectedTabIndex = tab.ordinal) {
                for (t in ContentsTab.entries) Tab(
                    selected = t == tab, onClick = { onTab(t) },
                    text = { Text(when (t) { ContentsTab.Contents -> Copy.contents; ContentsTab.Bookmarks -> Copy.bookmarksTab; ContentsTab.Highlights -> Copy.highlightsTab }) },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    ContentsTab.Contents -> TocList(toc, currentHref, onSelect, probe)
                    ContentsTab.Bookmarks -> BookmarksList(bookmarks, indicator, formatDate, onOpenBookmark, onRemoveBookmark)
                    ContentsTab.Highlights -> highlights()
                }
            }
        }
    }
}

@Composable
fun EmptyTab(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
