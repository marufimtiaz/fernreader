package app.fernreader.reader.contents

import app.fernreader.ui.IconLabel
import app.fernreader.ui.theme.AppIcons
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fernreader.core.data.Bookmark
import app.fernreader.reader.ContentsTab
import app.fernreader.reader.EventProbe
import app.fernreader.reader.PageIndicator
import app.fernreader.reader.bridge.TocItem
import app.fernreader.reader.toc.TocList
import app.fernreader.ui.Copy

/**
 * Full-screen panel behind the reader's contents button (F-041, F-062): tabs Contents | Bookmarks | Highlights. The
 * Contents tab is the default and the only one composed until another is picked (the contents list builds its tree on
 * composition, P-031). [highlights] is the slot the annotations list fills in (Task 8); empty, it shows a short hint.
 * A title row (the book's title, else "Contents") with Close sits above the tabs, like the search and display panels. A
 * book without a table of contents still opens the panel (its bookmarks and highlights are here); its Contents tab says so.
 */
@Composable
fun ContentsPanel(
    title: String,
    tab: ContentsTab, onTab: (ContentsTab) -> Unit,
    toc: List<TocItem>, currentHref: String?, onSelect: (TocItem) -> Unit,
    bookmarks: List<Bookmark>, indicator: PageIndicator, formatDate: (Long) -> String,
    onOpenBookmark: (Bookmark) -> Unit, onRemoveBookmark: (Bookmark) -> Unit,
    onDismiss: () -> Unit, probe: EventProbe,
    highlights: @Composable () -> Unit = { EmptyTab(Copy.noHighlights) },
    /** C-083: a bookmark's book page label (Book pages), else null. */
    bookPage: (Bookmark) -> String? = { null },
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title.ifBlank { Copy.contents }, Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onDismiss) { IconLabel(AppIcons.Close, Copy.close) }
            }
            TabRow(selectedTabIndex = tab.ordinal) {
                for (t in ContentsTab.entries) Tab(
                    selected = t == tab, onClick = { onTab(t) },
                    text = { Text(when (t) { ContentsTab.Contents -> Copy.contents; ContentsTab.Bookmarks -> Copy.bookmarksTab; ContentsTab.Highlights -> Copy.highlightsTab }) },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    ContentsTab.Contents -> if (toc.isEmpty()) EmptyTab(Copy.noContents) else TocList(toc, currentHref, onSelect, probe)
                    ContentsTab.Bookmarks -> BookmarksList(bookmarks, indicator, formatDate, onOpenBookmark, onRemoveBookmark, bookPage)
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
