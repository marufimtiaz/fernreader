package app.epubreader.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.epubreader.core.data.BookDetails
import app.epubreader.core.data.LibraryRepository
import app.epubreader.settings.Page
import app.epubreader.ui.Copy
import kotlinx.coroutines.launch

/** The details of [bookId], with C-096's two actions: after a merge the entry is gone, so the page closes. */
@Composable
fun BookDetailsPage(bookId: String, library: LibraryRepository, date: (Long) -> String, onBack: () -> Unit) {
    var changes by remember { mutableIntStateOf(0) }
    val d by produceState<BookDetails?>(null, bookId, changes) { value = library.details(bookId) }
    val scope = rememberCoroutineScope()
    BookDetailsScreen(
        d, date, onBack,
        onKeepSeparate = { path -> scope.launch { library.keepSeparate(path); changes++ } },
        // The other entry can go while the warning is open: nothing was lost then, and the page says so.
        onMerge = { scope.launch { if (library.mergeCopy(bookId)) onBack() else changes++ } },
    )
}

/** F-015 / F-021: metadata, every file the book is known under (duplicates), sizes, identity. */
@Composable
fun BookDetailsScreen(d: BookDetails?, date: (Long) -> String, onBack: () -> Unit, onKeepSeparate: (path: String) -> Unit = {}, onMerge: () -> Unit = {}) {
    var mergeAsked by remember { mutableStateOf(false) }
    Page(Copy.details, onBack) {
        if (d == null) { Text(Copy.bookGone, Modifier.padding(24.dp)); return@Page }
        SelectionContainer {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BookCover(d.title, d.coverThumb, Modifier.size(width = 96.dp, height = 144.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(d.title, style = MaterialTheme.typography.titleLarge)
                        Text(d.authors.joinToString(", ").ifEmpty { Copy.unknownAuthor }, style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Field(Copy.language, d.language ?: Copy.unknown)
                Field(Copy.added, date(d.addedAt))
                Field(Copy.lastRead, d.lastOpenedAt?.let(date) ?: Copy.never)
                if (d.collections.isNotEmpty()) Field(Copy.collections, d.collections.joinToString(", "))
                HorizontalDivider()
                Text(Copy.fileLocations(d.files.size), style = MaterialTheme.typography.titleMedium)
                d.files.forEach { f ->
                    Column {
                        Text(if (f.path.startsWith("/")) f.path.substringAfterLast('/') else f.path, style = MaterialTheme.typography.bodyLarge)
                        if (f.path.startsWith("/")) Text(Copy.displayFolder(f.path.substringBeforeLast('/')), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (f.size > 0) Text(Copy.fileSize(f.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (f.canSeparate) TextButton(onClick = { onKeepSeparate(f.path) }) { Text(Copy.keepSeparate) }
                    }
                }
                if (d.separate) {
                    Text(Copy.separateCopyNote, style = MaterialTheme.typography.bodyMedium)
                    if (d.canMerge) TextButton(onClick = { mergeAsked = true }) { Text(Copy.mergeCopy) }
                    else Text(Copy.noOtherCopy, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                Field(Copy.identity, d.identityKey)
            }
        }
        if (mergeAsked) {
            val r = d.readingData
            ConfirmDialog(
                Copy.mergeCopyTitle, Copy.mergeCopyWarning(r.position, r.bookmarks, r.highlights, r.notes), Copy.mergeCopyConfirm,
                onConfirm = { mergeAsked = false; onMerge() }, onCancel = { mergeAsked = false },
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
