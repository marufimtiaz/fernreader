package app.fernreader.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.fernreader.core.data.ScanFailureEntity
import app.fernreader.ui.Copy

/** F-016 details: each file that could not be opened, its folder and a short reason. */
@Composable
fun FailureSheet(failures: List<ScanFailureEntity>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text(Copy.close) } },
        title = { Text(Copy.failuresTitle) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(failures, key = { it.path }) { f ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(f.name, style = MaterialTheme.typography.titleSmall)
                        Text(Copy.displayFolder(f.folder), style = MaterialTheme.typography.bodySmall)
                        Text(Copy.failReason(f.reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
    )
}
