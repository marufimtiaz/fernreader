package app.epubreader.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.snapshotFlow
import app.epubreader.ui.theme.Glyphs
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
import androidx.compose.material3.AlertDialog
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

/** What the library screen asks the app to do (routes and system pickers). */
class LibraryActions(
    val onOpen: (LibraryBook) -> Unit,
    val onOpenPicker: () -> Unit,
    val onRescan: () -> Unit,
    val onSettings: () -> Unit = {},
    val onAbout: () -> Unit = {},
    val onDetails: (bookId: String) -> Unit = {},
    /** C-075: the library or one of its views came into view (an incremental scan runs). */
    val onShown: () -> Unit = {},
)

private sealed interface Dialog {
    data object NewCollection : Dialog
    /** "New collection" from the add-to-collection picker: the selection goes into it. */
    data object NewCollectionForSelection : Dialog
    data class Rename(val id: String, val name: String) : Dialog
    data class DeleteCollection(val id: String, val name: String) : Dialog
    data object PickCollection : Dialog
    data object DeleteBooks : Dialog
    data object AddBooks : Dialog
}

/**
 * F-020..F-028, F-016: the home screen with its navigation drawer. Opaque (the Surface also blocks touches to the
 * reader WebView below).
 */
@Composable
fun LibraryScreen(state: LibraryState, actions: LibraryActions, probe: EventProbe) {
    val ui by state.ui.collectAsState()
    var failuresOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    if (ui.books.isNotEmpty()) LaunchedEffect(Unit) {
        state.shown.value = true
        if (!firstBookLogged) { firstBookLogged = true; probe.ev("library_first_book", "") }
    }
    val topTitle = ui.books.firstOrNull()?.title
    // Bench (bench/device_pass.sh return): the book on top of the view changed (a new book sorts first, C-076)
    LaunchedEffect(topTitle) { if (topTitle != null) probe.ev("library_top", topTitle) }
    LaunchedEffect(ui.view) { actions.onShown() } // entering the library route, and every change of view (C-075)
    LaunchedEffect(ui.drawerOpen) { if (ui.drawerOpen) drawer.open() else drawer.close() }
    LaunchedEffect(drawer) { snapshotFlow { drawer.currentValue }.collect { state.setDrawer(it == DrawerValue.Open) } }
    LaunchedEffect(state) { for (m in state.messages) snackbar.showSnackbar(m) } // one at a time, each shown in full

    ModalNavigationDrawer(
        drawerState = drawer, gesturesEnabled = ui.drawerOpen,
        drawerContent = {
            Drawer(ui, onView = state::openView, onToggleFolder = state::toggleDrawerFolder, onNewCollection = { state.setDrawer(false); dialog = Dialog.NewCollection },
                onSettings = { state.setDrawer(false); actions.onSettings() }, onAbout = { state.setDrawer(false); actions.onAbout() })
        },
    ) {
        Surface(Modifier.fillMaxSize()) {
            Box {
                Column(Modifier.safeDrawingPadding()) {
                    if (ui.selected.isNotEmpty()) SelectionBar(ui, state, onAddTo = { dialog = Dialog.PickCollection }, onDelete = { dialog = Dialog.DeleteBooks },
                        onDetails = { actions.onDetails(ui.selected.first()) })
                    else Header(ui, state, actions, onDialog = { dialog = it })
                    if (ui.scan.showsProgress) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))
                    val folder = ui.view as? LibraryView.Folder
                    if (folder != null) FolderBar(folder.path, ui.includeSubfolders, state::setIncludeSubfolders)
                    Box(Modifier.weight(1f)) {
                        when {
                            !ui.loaded -> Unit
                            ui.total == 0 && ui.view == LibraryView.All -> EmptyLibrary(ui.scan.running, actions.onRescan, actions.onOpenPicker)
                            ui.view == LibraryView.Folders -> FolderList(ui.folderTree, onOpen = { state.pushView(LibraryView.Folder(it)) }, onToggle = state::toggleFolder)
                            ui.inView == 0 -> EmptyView(ui.view, onAddBooks = { dialog = Dialog.AddBooks }, onAllBooks = { state.openView(LibraryView.All) })
                            ui.books.isEmpty() -> Text(Copy.noMatch(ui.query), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
                            ui.layout == LibraryLayout.GRID -> Grid(ui.books, ui.query, ui.selected, state, actions.onOpen, probe)
                            else -> ListLayout(ui.books, ui.query, ui.selected, state, actions.onOpen, probe)
                        }
                    }
                    if (ui.failureNoticeVisible) FailureNotice(ui.failures.size, onDetails = { failuresOpen = true }, onDismiss = state::dismissFailures)
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
            }
        }
    }
    if (failuresOpen) FailureSheet(ui.failures, onClose = { failuresOpen = false })
    ui.missing?.let { b ->
        AlertDialog(
            onDismissRequest = state::dismissMissing,
            title = { Text(Copy.missingTitle) },
            text = { Text(Copy.missingBody(b.title)) },
            confirmButton = { TextButton(onClick = state::removeMissing) { Text(Copy.removeEntry) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { state.dismissMissing(); actions.onRescan() }) { Text(Copy.rescan) }
                    TextButton(onClick = state::dismissMissing) { Text(Copy.cancel) }
                }
            },
        )
    }
    val close = { dialog = null }
    when (val d = dialog) {
        null -> Unit
        Dialog.NewCollection, Dialog.NewCollectionForSelection -> CollectionNameDialog(Copy.newCollection, "", ui.collections, null,
            onDone = { state.createCollection(it, thenAddSelected = d == Dialog.NewCollectionForSelection); close() }, onCancel = close)
        is Dialog.Rename -> CollectionNameDialog(Copy.renameCollection, d.name, ui.collections, d.id,
            onDone = { state.renameCollection(d.id, it); close() }, onCancel = close)
        is Dialog.DeleteCollection -> ConfirmDialog(Copy.deleteCollectionTitle(d.name), Copy.deleteCollectionBody, Copy.delete,
            onConfirm = { state.deleteCollection(d.id); close() }, onCancel = close)
        Dialog.PickCollection -> CollectionPickerDialog(ui.collections, onPick = { state.addSelectedTo(it); close() },
            onNew = { dialog = Dialog.NewCollectionForSelection }, onCancel = close)
        Dialog.DeleteBooks -> {
            val all by state.allBooks.collectAsState(emptyList())
            val files = all.filter { it.id in ui.selected }.sumOf { b -> b.paths.count { it.startsWith("/") } }
            DeleteBooksDialog(ui.selected.size, files, onDelete = { state.deleteSelected(it); close() }, onCancel = close)
        }
        Dialog.AddBooks -> {
            val all by state.allBooks.collectAsState(emptyList())
            val id = (ui.view as? LibraryView.Collection)?.id
            AddBooksDialog(all.filter { id !in it.collectionIds }, onAdd = { state.addToCurrentCollection(it); close() }, onCancel = close)
        }
    }
}

@Composable
private fun Drawer(ui: LibraryUi, onView: (LibraryView) -> Unit, onToggleFolder: (String) -> Unit, onNewCollection: () -> Unit,
                   onSettings: () -> Unit, onAbout: () -> Unit) {
    ModalDrawerSheet {
        LazyColumn(Modifier.padding(horizontal = 12.dp)) {
            item { Text(Copy.appName, Modifier.padding(start = 16.dp, top = 20.dp, bottom = 12.dp), style = MaterialTheme.typography.titleLarge) }
            item {
                NavigationDrawerItem(label = { Text(Copy.allBooks) }, icon = { Icon(Glyphs.Books, null) }, selected = ui.view == LibraryView.All,
                    badge = { Text(ui.total.toString()) }, onClick = { onView(LibraryView.All) })
            }
            // C-074: the folders with books, one tap away (the folder-view setting hides the section)
            val folders = ui.drawerFolders
            if (ui.folderViewEnabled && folders.rows.isNotEmpty()) {
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                item { SectionLabel(Copy.folders) }
                items(folders.rows, key = { "folder:" + it.node.path }) { f ->
                    DrawerFolderRow(f, selected = ui.view == LibraryView.Folder(f.node.path), onOpen = { onView(LibraryView.Folder(f.node.path)) },
                        onToggle = { onToggleFolder(f.node.path) })
                }
                if (folders.showAll) item {
                    TextButton(onClick = { onView(LibraryView.Folders) }, Modifier.padding(start = 4.dp)) { Text(Copy.showAllFolders) }
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionLabel(Copy.collections) }
            items(ui.collections, key = { it.id }) { c ->
                NavigationDrawerItem(label = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, icon = { Icon(Glyphs.Shelf, null) },
                    selected = ui.view == LibraryView.Collection(c.id), badge = { Text(c.count.toString()) }, onClick = { onView(LibraryView.Collection(c.id)) })
            }
            item { NavigationDrawerItem(label = { Text(Copy.newCollection) }, icon = { Icon(Glyphs.Plus, null) }, selected = false, onClick = onNewCollection) }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { NavigationDrawerItem(label = { Text(Copy.settings) }, icon = { Icon(Glyphs.Settings, null) }, selected = false, onClick = onSettings) }
            item { NavigationDrawerItem(label = { Text(Copy.about) }, icon = { Icon(Glyphs.Info, null) }, selected = false, onClick = onAbout) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** C-074: a folder in the drawer: the chevron folds its subfolders, the rest of the row opens it. */
@Composable
private fun DrawerFolderRow(f: DrawerFolder, selected: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    val n = f.node
    val name = Copy.folderName(n.name)
    NavigationDrawerItem(
        modifier = Modifier.padding(start = (16 * n.depth.coerceAtMost(4)).dp),
        label = {
            Column {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (f.parentLine != null) Text(f.parentLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.StartEllipsis)
            }
        },
        icon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (n.hasChildren) IconButton(onClick = onToggle, Modifier.size(32.dp)) {
                    Icon(if (n.expanded) Glyphs.ChevronDown else Glyphs.ChevronRight, if (n.expanded) Copy.collapseFolder(name) else Copy.expandFolder(name), Modifier.size(20.dp))
                } else Spacer(Modifier.size(32.dp))
                Icon(Glyphs.Folder, null)
            }
        },
        badge = { Text(Copy.folderCount(n.direct, n.total)) },
        selected = selected, onClick = onOpen,
    )
}

@Composable
private fun Header(ui: LibraryUi, state: LibraryState, actions: LibraryActions, onDialog: (Dialog) -> Unit) {
    var sortOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val collection = (ui.view as? LibraryView.Collection)?.let { v -> ui.collections.firstOrNull { it.id == v.id } }
    Column(Modifier.padding(start = 4.dp, end = 8.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ui.nested) IconButton(onClick = { state.back() }) { Icon(Glyphs.Back, Copy.back) }
            else IconButton(onClick = { state.setDrawer(true) }) { Icon(Glyphs.Menu, Copy.openMenu) }
            Text(ui.viewTitle ?: if (ui.view == LibraryView.Folders) Copy.folders else Copy.appTitle, Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Glyphs.More, Copy.more) }
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (collection != null) {
                        DropdownMenuItem(text = { Text(Copy.addBooks) }, onClick = { menuOpen = false; onDialog(Dialog.AddBooks) })
                        DropdownMenuItem(text = { Text(Copy.renameCollection) }, onClick = { menuOpen = false; onDialog(Dialog.Rename(collection.id, collection.name)) })
                        DropdownMenuItem(text = { Text(Copy.deleteCollection) }, onClick = { menuOpen = false; onDialog(Dialog.DeleteCollection(collection.id, collection.name)) })
                        HorizontalDivider()
                    }
                    DropdownMenuItem(text = { Text(Copy.rescan) }, onClick = { menuOpen = false; actions.onRescan() })
                    DropdownMenuItem(text = { Text(Copy.openBook) }, onClick = { menuOpen = false; actions.onOpenPicker() })
                }
            }
        }
        if (ui.view != LibraryView.Folders) {
            OutlinedTextField(
                ui.query, state::setQuery, Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp), singleLine = true,
                placeholder = { Text(Copy.searchHint) },
            )
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { sortOpen = true }) { Text(Copy.sortLabel(ui.sort)) }
                    DropdownMenu(sortOpen, onDismissRequest = { sortOpen = false }) {
                        LibrarySort.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(Copy.sortName(s)) }, onClick = { state.setSort(s); sortOpen = false })
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { state.setLayout(if (ui.layout == LibraryLayout.GRID) LibraryLayout.LIST else LibraryLayout.GRID) }) {
                    Text(if (ui.layout == LibraryLayout.GRID) Copy.listView else Copy.gridView)
                }
            }
        }
    }
}

/** F-024: selection mode replaces the header. */
@Composable
private fun SelectionBar(ui: LibraryUi, state: LibraryState, onAddTo: () -> Unit, onDelete: () -> Unit, onDetails: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = state::clearSelection) { Icon(Glyphs.Close, Copy.endSelection) }
            Text(Copy.selectedCount(ui.selected.size), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = state::selectAll) { Icon(Glyphs.SelectAll, Copy.selectAll) }
            TextButton(onClick = onAddTo) { Text(Copy.addToCollectionShort) }
            IconButton(onClick = onDelete) { Icon(Glyphs.Trash, Copy.delete) }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Glyphs.More, Copy.more) }
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (ui.view is LibraryView.Collection) DropdownMenuItem(text = { Text(Copy.removeFromCollection) }, onClick = { menuOpen = false; state.removeSelectedFromCollection() })
                    DropdownMenuItem(text = { Text(Copy.details) }, enabled = ui.selected.size == 1, onClick = { menuOpen = false; onDetails() })
                }
            }
        }
    }
}

@Composable
private fun FolderBar(path: String, includeSubfolders: Boolean, onInclude: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Copy.displayFolder(path), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.StartEllipsis)
        Text(Copy.includeSubfolders, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelLarge)
        Switch(includeSubfolders, onInclude)
    }
}

/** F-017: the folder tree, with book counts; tap opens the folder, the chevron folds its subfolders. */
@Composable
private fun FolderList(rows: List<FolderNode>, onOpen: (String) -> Unit, onToggle: (String) -> Unit) {
    if (rows.isEmpty()) { Text(Copy.noFolders, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge); return }
    LazyColumn {
        items(rows, key = { it.path }) { r ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(r.path) }.padding(start = (8 + 20 * r.depth).dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (r.hasChildren) IconButton(onClick = { onToggle(r.path) }) {
                    Icon(if (r.expanded) Glyphs.ChevronDown else Glyphs.ChevronRight, if (r.expanded) Copy.collapse else Copy.expand)
                } else Spacer(Modifier.size(48.dp))
                Icon(Glyphs.Folder, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(Copy.folderName(r.name), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (r.parent.isNotEmpty()) Text(r.parent, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.StartEllipsis)
                }
                Text(Copy.folderCount(r.direct, r.total), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyView(view: LibraryView, onAddBooks: () -> Unit, onAllBooks: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (view is LibraryView.Collection) Copy.emptyCollection else Copy.emptyFolder, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (view is LibraryView.Collection) Button(onClick = onAddBooks) { Text(Copy.addBooks) }
        OutlinedButton(onClick = onAllBooks) { Text(Copy.allBooks) }
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

/** Tap opens (or, while selecting, toggles); long-press starts selection (F-024). */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.bookClicks(b: LibraryBook, selecting: Boolean, state: LibraryState, onOpen: (LibraryBook) -> Unit) =
    combinedClickable(
        onClick = { if (selecting) state.toggleSelection(b.id) else onOpen(b) },
        onLongClick = { if (selecting) state.toggleSelection(b.id) else state.startSelection(b.id) },
    )

@Composable
private fun SelectedMark(modifier: Modifier) {
    Box(modifier.size(26.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Glyphs.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun Grid(books: List<LibraryBook>, query: String, selected: Set<String>, state: LibraryState, onOpen: (LibraryBook) -> Unit, probe: EventProbe) {
    LazyVerticalGrid(GridCells.Adaptive(112.dp), contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(books, key = { _, b -> b.id }) { i, b ->
            val on = b.id in selected
            Column(Modifier.firstEntry(i, probe).bookClicks(b, selected.isNotEmpty(), state, onOpen)) {
                Box {
                    BookCover(b.title, b.coverThumb, Modifier.fillMaxWidth().aspectRatio(2f / 3f).then(
                        if (on) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)) else Modifier))
                    if (on) SelectedMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
                }
                Text(emphasized(b.title, query), Modifier.fillMaxWidth().padding(top = 6.dp), style = titleStyle(b, MaterialTheme.typography.bodyMedium),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(emphasized(authorLine(b), query), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ListLayout(books: List<LibraryBook>, query: String, selected: Set<String>, state: LibraryState, onOpen: (LibraryBook) -> Unit, probe: EventProbe) {
    LazyColumn {
        itemsIndexed(books, key = { _, b -> b.id }) { i, b ->
            val on = b.id in selected
            Row(Modifier.firstEntry(i, probe).fillMaxWidth()
                .then(if (on) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                .bookClicks(b, selected.isNotEmpty(), state, onOpen).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    BookCover(b.title, b.coverThumb, Modifier.size(width = 48.dp, height = 72.dp), small = true)
                    if (on) SelectedMark(Modifier.align(Alignment.Center))
                }
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

