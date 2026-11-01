package app.epubreader.reader.controls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.epubreader.ui.Copy

/** Actions of the controls overlay (F-033). The reader controller supplies them; the overlay owns no state but the slider drag. */
class ControlActions(
    val back: () -> Unit,
    val search: () -> Unit,
    val bookmark: () -> Unit,
    val contents: () -> Unit,
    val display: () -> Unit,
    val settings: () -> Unit,
    val seek: (Double) -> Unit,
    /** C-073: the slider is dragged to a fraction (not yet released). */
    val previewSeek: (Double) -> Unit,
    /** C-073: a drag ended without a seek (the bar went away mid-drag). */
    val cancelSeek: () -> Unit,
    /** C-073: the title was tapped: show the full title and the authors. */
    val bookInfo: () -> Unit,
)

/**
 * Top half of the controls: back, the book title (two lines at most; a tap shows it in full, C-073), search, bookmark,
 * contents. Rounded where it meets the page; floats over the page (it never changes the page's size).
 */
@Composable
fun ReaderTopBar(bookTitle: String, bookmarked: Boolean, a: ControlActions, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = a.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, Copy.back) }
            Text(
                bookTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = Copy.bookDetails, role = Role.Button, onClick = a.bookInfo)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
            IconButton(onClick = a.search) { Icon(Icons.Filled.Search, Copy.search) }
            IconButton(onClick = a.bookmark) {
                if (bookmarked) Icon(BookmarkFilledIcon, Copy.removeBookmark, tint = MaterialTheme.colorScheme.primary) else Icon(BookmarkIcon, Copy.addBookmark)
            }
            // Always enabled: the panel also holds the bookmarks and highlights, which a book without a TOC has too.
            IconButton(onClick = a.contents) { Icon(Icons.AutoMirrored.Filled.List, Copy.contents) }
        }
    }
}

/** C-073: the full title and the authors, from the title in the top bar; Back or a tap outside closes it. */
@Composable
fun BookInfoDialog(title: String, authors: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(Copy.authorsLine(authors)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Copy.close) } },
    )
}

/**
 * Bottom half: the chapter (start) and page label (end) on one row, the seek slider, display and reader settings.
 * While the slider is dragged, [chapter] and [pageLabel] are the target's (C-073: the caller passes the preview).
 */
@Composable
fun ReaderBottomBar(chapter: String?, pageLabel: String?, fraction: Double, a: ControlActions, modifier: Modifier = Modifier) {
    var drag by remember { mutableStateOf<Float?>(null) }
    // The bar going away mid-drag (Back, the controls hidden) is a cancelled drag: nothing seeks, the preview ends.
    DisposableEffect(Unit) { onDispose { if (drag != null) a.cancelSeek() } }
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(chapter ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f))
                Text(pageLabel ?: "", maxLines = 1, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val shown = drag ?: fraction.toFloat()
                Slider(
                    // The slider also reports the value it is given when that changes (the page moved, a seek landed):
                    // only a value of the user's own is a drag.
                    value = shown, onValueChange = { if (drag != null || it != shown) { drag = it; a.previewSeek(it.toDouble()) } },
                    onValueChangeFinished = { drag?.let { a.seek(it.toDouble()) }; drag = null },
                    modifier = Modifier.weight(1f).semantics { contentDescription = Copy.seek },
                )
                IconButton(onClick = a.display, modifier = Modifier.semantics { contentDescription = Copy.display }) {
                    Text("Aa", style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onClick = a.settings) { Icon(Icons.Filled.Settings, Copy.readerSettings) }
            }
        }
    }
}

/** Outline bookmark ribbon (not in the core icon set). */
private val BookmarkIcon: ImageVector = ImageVector.Builder("Bookmark", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
        moveTo(17f, 3f); horizontalLineTo(7f)
        curveTo(5.9f, 3f, 5f, 3.9f, 5f, 5f)
        verticalLineTo(21f); lineTo(12f, 18f); lineTo(19f, 21f); verticalLineTo(5f)
        curveTo(19f, 3.9f, 18.1f, 3f, 17f, 3f); close()
        moveTo(17f, 18f); lineTo(12f, 15.8f); lineTo(7f, 18f); verticalLineTo(5f); horizontalLineTo(17f); close()
    }
}.build()

/** Filled ribbon: the bookmark button on a bookmarked page, and the page marker. */
val BookmarkFilledIcon: ImageVector = ImageVector.Builder("BookmarkFilled", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(17f, 3f); horizontalLineTo(7f)
        curveTo(5.9f, 3f, 5f, 3.9f, 5f, 5f)
        verticalLineTo(21f); lineTo(12f, 18f); lineTo(19f, 21f); verticalLineTo(5f)
        curveTo(19f, 3.9f, 18.1f, 3f, 17f, 3f); close()
    }
}.build()
