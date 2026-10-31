package app.epubreader.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.epubreader.core.data.LibraryBook
import app.epubreader.reader.EventProbe
import app.epubreader.ui.Copy

/** Set once per process: the first time a non-empty library is composed (bench P-050 "first book visible"). */
private var firstBookLogged = false

/** Bench: where the first entry is on screen ("row0", tapped by bench/groups/open.sh). Logged once per composition. */
@Composable
private fun Modifier.firstEntry(index: Int, probe: EventProbe): Modifier {
    if (index != 0) return this
    val logged = remember { booleanArrayOf(false) }
    return onGloballyPositioned { c ->
        if (!logged[0]) { logged[0] = true; val p = c.boundsInWindow().center; probe.ev("row0", "x=${p.x.toInt()} y=${p.y.toInt()}") }
    }
}

/**
 * F-020..F-023, F-016: the home screen. Opaque (the Surface also blocks touches to the reader WebView below).
 */
@Composable
fun LibraryScreen(state: LibraryState, onOpen: (LibraryBook) -> Unit, onOpenPicker: () -> Unit, onRescan: () -> Unit, probe: EventProbe) {
    val ui by state.ui.collectAsState()
    var failuresOpen by remember { mutableStateOf(false) }
    if (ui.books.isNotEmpty() && !firstBookLogged) LaunchedEffect(Unit) { firstBookLogged = true; probe.ev("library_first_book", "") }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Header(ui, state, onOpenPicker, onRescan)
            if (ui.scan.running) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))
            Box(Modifier.weight(1f)) {
                when {
                    !ui.loaded -> Unit
                    ui.total == 0 -> EmptyLibrary(ui.scan.running, onRescan, onOpenPicker)
                    ui.books.isEmpty() -> Text(Copy.noMatch(ui.query), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
                    ui.layout == LibraryLayout.GRID -> Grid(ui.books, ui.query, onOpen, probe)
                    else -> ListLayout(ui.books, ui.query, onOpen, probe)
                }
            }
            if (ui.failureNoticeVisible) FailureNotice(ui.failures.size, onDetails = { failuresOpen = true }, onDismiss = state::dismissFailures)
        }
    }
    if (failuresOpen) FailureSheet(ui.failures, onClose = { failuresOpen = false })
}

@Composable
private fun Header(ui: LibraryUi, state: LibraryState, onOpenPicker: () -> Unit, onRescan: () -> Unit) {
    var sortOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Copy.appTitle, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { state.setLayout(if (ui.layout == LibraryLayout.GRID) LibraryLayout.LIST else LibraryLayout.GRID) }) {
                Text(if (ui.layout == LibraryLayout.GRID) Copy.listView else Copy.gridView)
            }
            Box {
                TextButton(onClick = { sortOpen = true }) { Text(Copy.sortLabel(ui.sort)) }
                DropdownMenu(sortOpen, onDismissRequest = { sortOpen = false }) {
                    LibrarySort.entries.forEach { s ->
                        DropdownMenuItem(text = { Text(Copy.sortName(s)) }, onClick = { state.setSort(s); sortOpen = false })
                    }
                }
            }
            Box {
                TextButton(onClick = { menuOpen = true }) { Text(Copy.more) }
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(Copy.rescan) }, onClick = { menuOpen = false; onRescan() })
                    DropdownMenuItem(text = { Text(Copy.openBook) }, onClick = { menuOpen = false; onOpenPicker() })
                }
            }
        }
        OutlinedTextField(
            ui.query, state::setQuery, Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 8.dp), singleLine = true,
            placeholder = { Text(Copy.searchHint) },
        )
    }
}

@Composable
private fun EmptyLibrary(scanning: Boolean, onRescan: () -> Unit, onOpenPicker: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (scanning) Copy.looking else Copy.libraryEmpty, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (!scanning) {
            Button(onClick = onRescan) { Text(Copy.rescan) }
            OutlinedButton(onClick = onOpenPicker) { Text(Copy.openBook) }
        }
    }
}

@Composable
private fun Grid(books: List<LibraryBook>, query: String, onOpen: (LibraryBook) -> Unit, probe: EventProbe) {
    LazyVerticalGrid(GridCells.Adaptive(112.dp), contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(books, key = { _, b -> b.id }) { i, b ->
            Column(Modifier.firstEntry(i, probe).clickable { onOpen(b) }) {
                BookCover(b.title, b.coverThumb, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                Text(emphasized(b.title, query), Modifier.fillMaxWidth().padding(top = 6.dp), style = titleStyle(b, MaterialTheme.typography.bodyMedium),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(emphasized(authorLine(b), query), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ListLayout(books: List<LibraryBook>, query: String, onOpen: (LibraryBook) -> Unit, probe: EventProbe) {
    LazyColumn {
        itemsIndexed(books, key = { _, b -> b.id }) { i, b ->
            Row(Modifier.firstEntry(i, probe).fillMaxWidth().clickable { onOpen(b) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(b.title, b.coverThumb, Modifier.size(width = 48.dp, height = 72.dp), small = true)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(emphasized(b.title, query), Modifier.fillMaxWidth(), style = titleStyle(b, MaterialTheme.typography.titleMedium),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(emphasized(authorLine(b), query), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun FailureNotice(count: Int, onDetails: () -> Unit, onDismiss: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Copy.failuresNotice(count), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDetails) { Text(Copy.details) }
            TextButton(onClick = onDismiss) { Text(Copy.dismiss) }
        }
    }
}

/** F-021: RTL titles are right-aligned. */
private fun titleStyle(b: LibraryBook, base: TextStyle): TextStyle =
    if (b.titleDirection == "rtl") base.copy(textDirection = TextDirection.Rtl, textAlign = TextAlign.Right) else base

private fun authorLine(b: LibraryBook) = b.authors.joinToString(", ").ifEmpty { Copy.unknownAuthor }

/** F-023: the matched text is bold and underlined. */
private fun emphasized(text: String, query: String): AnnotatedString {
    val ranges = matchRanges(text, query)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { addStyle(SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline), it.first, it.last + 1) }
    }
}

