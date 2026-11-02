package app.epubreader.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.epubreader.core.data.CollectionEntity
import app.epubreader.core.data.CollectionNames
import app.epubreader.core.data.LibraryBook
import app.epubreader.ui.Copy
import app.epubreader.ui.theme.AppIcons

/** F-026: create or rename a collection. The name is required and must not be taken. */
@Composable
fun CollectionNameDialog(title: String, initial: String, existing: List<CollectionSummary>, renaming: String?, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val problem = CollectionNames.problem(name, existing.map { CollectionEntity(it.id, it.name, 0, 0, false) }, renaming)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                name, { name = it }, singleLine = true, label = { Text(Copy.collectionName) },
                isError = problem == CollectionNames.Problem.TAKEN,
                supportingText = { if (problem == CollectionNames.Problem.TAKEN) Text(Copy.nameTaken) },
            )
        },
        confirmButton = { TextButton(onClick = { onDone(name.trim()) }, enabled = problem == null) { Text(Copy.save) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(Copy.cancel) } },
    )
}

/** F-024 "add to collection": pick one, or start a new one. */
@Composable
fun CollectionPickerDialog(collections: List<CollectionSummary>, onPick: (String) -> Unit, onNew: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(Copy.addToCollection) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(collections, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(c.id) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Collection, null, Modifier.size(20.dp))
                        Text(c.name, Modifier.padding(start = 16.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().clickable(onClick = onNew).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Add, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(Copy.newCollection, Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(Copy.cancel) } },
    )
}

/** F-025: confirmation, with "also delete the file" off by default. */
@Composable
fun DeleteBooksDialog(count: Int, fileCount: Int, onDelete: (deleteFiles: Boolean) -> Unit, onCancel: () -> Unit) {
    var files by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(Copy.deleteTitle(count)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (files) Copy.deleteFilesBody(fileCount) else Copy.deleteLibraryOnlyBody)
                Row(Modifier.fillMaxWidth().clickable { files = !files }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(files, { files = it })
                    Text(Copy.alsoDeleteFiles(fileCount))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDelete(files) }) { Text(if (files) Copy.deleteFiles else Copy.remove, color = if (files) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(Copy.cancel) } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(Copy.cancel) } },
    )
}

/** F-026 "add books from a picker inside the collection": every library book not in it yet, with a filter. */
@Composable
fun AddBooksDialog(candidates: List<LibraryBook>, onAdd: (List<String>) -> Unit, onCancel: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    val shown = remember(candidates, query) { LibraryState.arrange(candidates, query, LibrarySort.TITLE) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(Copy.addBooks) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(Copy.searchHint) })
                if (shown.isEmpty()) Text(if (candidates.isEmpty()) Copy.allBooksAlreadyIn else Copy.noMatch(query), Modifier.padding(vertical = 16.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.id }) { b ->
                        Row(Modifier.fillMaxWidth().clickable { picked = if (b.id in picked) picked - b.id else picked + b.id }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(b.id in picked, { picked = if (it) picked + b.id else picked - b.id })
                            Column(Modifier.weight(1f)) {
                                Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(b.authors.joinToString(", ").ifEmpty { Copy.unknownAuthor }, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(picked.toList()) }, enabled = picked.isNotEmpty()) { Text(Copy.addCount(picked.size)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(Copy.cancel) } },
    )
}
