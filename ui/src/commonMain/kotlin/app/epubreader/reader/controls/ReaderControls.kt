package app.epubreader.reader.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.epubreader.ui.BarIcon
import app.epubreader.ui.Copy
import app.epubreader.ui.theme.AppIcons

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
            BarIcon(AppIcons.Back, Copy.back, onClick = a.back)
            Text(
                bookTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = Copy.bookDetails, role = Role.Button, onClick = a.bookInfo)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
            BarIcon(AppIcons.Search, Copy.search, onClick = a.search)
            if (bookmarked) BarIcon(AppIcons.Bookmarked, Copy.removeBookmark, tint = MaterialTheme.colorScheme.primary, onClick = a.bookmark)
            else BarIcon(AppIcons.Bookmark, Copy.addBookmark, onClick = a.bookmark)
            // Always enabled: the panel also holds the bookmarks and highlights, which a book without a TOC has too.
            BarIcon(AppIcons.Contents, Copy.contents, onClick = a.contents)
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
 * C-083/C-086: the page label as the "Go to page" entry: a 48 dp target (ripple, button role, an accessible name that
 * keeps the page shown) around a tonal chip with the label and a dropdown mark, so it reads as tappable.
 */
@Composable
private fun GoToPageButton(pageLabel: String, spoken: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val entry = Copy.goToPageEntry(spoken)
    Box(
        modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClickLabel = Copy.goToPage, onClick = onClick)
            .semantics { contentDescription = entry }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp)).padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(pageLabel, maxLines = 1, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Icon(AppIcons.GoToPage, contentDescription = null, Modifier.padding(start = 2.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/**
 * Bottom half: the chapter (start) and page label (end) on one row, the seek slider, display and reader settings.
 * While the slider is dragged, [chapter] and [pageLabel] are the target's (C-073: the caller passes the preview).
 */
@Composable
fun ReaderBottomBar(
    chapter: String?, pageLabel: String?, pageSpoken: String?, fraction: Double, a: ControlActions, modifier: Modifier = Modifier,
    /** C-083: tapping the page label opens "Go to page" (Book pages); null = not offered. */
    onPageLabel: (() -> Unit)? = null,
) {
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
            // 48 dp tall whether or not the page label is the Go to page button (no jump when a drag's preview replaces it).
            Row(Modifier.padding(end = 12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(chapter ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f))
                if (onPageLabel != null && pageLabel != null) GoToPageButton(pageLabel, pageSpoken ?: pageLabel, onPageLabel, Modifier.padding(start = 4.dp))
                else Text(pageLabel ?: "", maxLines = 1, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp).semantics { pageSpoken?.let { contentDescription = it } })
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
                BarIcon(AppIcons.Display, Copy.display, onClick = a.display)
                BarIcon(AppIcons.Settings, Copy.readerSettings, onClick = a.settings)
            }
        }
    }
}
