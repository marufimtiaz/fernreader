package app.fernreader.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.fernreader.ui.Copy

/** F-090: restore offer, progress, result, and problems. Shown over any screen (a backup can come from a file manager). */
@Composable
fun BackupDialogs(ui: BackupUi, onConfirm: () -> Unit, onDismiss: () -> Unit, date: (Long) -> String) {
    when {
        ui.offer != null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(Copy.restoreTitle) },
            text = { Text(Copy.restoreBody(date(ui.offer.createdAt), ui.offer.books, ui.offer.collections)) },
            confirmButton = { TextButton(onClick = onConfirm) { Text(Copy.restore) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(Copy.cancel) } },
        )
        ui.busy -> AlertDialog(onDismissRequest = {}, title = { Text(Copy.restoring) }, text = { CircularProgressIndicator() }, confirmButton = {})
        ui.result != null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(Copy.restoredTitle) },
            text = { Text(Copy.restoredBody(ui.result)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(Copy.ok) } },
        )
        ui.savedAs != null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(Copy.backupSavedTitle) },
            text = { Text(Copy.backupSavedBody(ui.savedAs)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(Copy.ok) } },
        )
        ui.problem != null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(Copy.backupProblemTitle) },
            text = { Text(Copy.backupProblem(ui.problem)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(Copy.ok) } },
        )
    }
}
