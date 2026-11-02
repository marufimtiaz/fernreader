package app.epubreader.reader.contents

import app.epubreader.ui.BarIcon
import app.epubreader.ui.theme.AppIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.epubreader.core.data.Bookmark
import app.epubreader.reader.PageIndicator
import app.epubreader.reader.bookmarkPage
import app.epubreader.reader.bookmarkPercent
import app.epubreader.ui.Copy

/**
 * The Bookmarks tab (F-041): one row per bookmark in book order: chapter, then place and date. Tap = jump, the icon
 * removes at once (the confirmation appears over the panel). Live: [bookmarks] is the database's flow.
 */
@Composable
fun BookmarksList(
    bookmarks: List<Bookmark>, indicator: PageIndicator, formatDate: (Long) -> String,
    onOpen: (Bookmark) -> Unit, onRemove: (Bookmark) -> Unit,
    /** C-083: the bookmark's book page label while Book pages are shown (null: the estimate below). */
    bookPage: (Bookmark) -> String? = { null },
) {
    if (bookmarks.isEmpty()) { EmptyTab(Copy.noBookmarks); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(bookmarks, key = { it.id }) { b ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(b) }.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    Text(b.chapter.ifBlank { Copy.untitledEntry }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    val place = bookPage(b)?.let(Copy::bookPage) ?: bookmarkPage(b.fraction, indicator)?.let(Copy::bookmarkPage) ?: Copy.bookmarkPercent(bookmarkPercent(b.fraction))
                    Text("$place · ${formatDate(b.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BarIcon(AppIcons.Delete, Copy.removeBookmark) { onRemove(b) }
            }
            HorizontalDivider()
        }
    }
}
