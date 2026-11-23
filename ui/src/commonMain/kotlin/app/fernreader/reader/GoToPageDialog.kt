package app.fernreader.reader

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import app.fernreader.ui.Copy

/**
 * C-083 "Go to page": a page label as the book prints it ("xii") or a number. [range] is the first and last label
 * (shown under the field); [go] returns false when the book has no such page (the dialog stays and says so).
 */
@Composable
fun GoToPageDialog(range: Pair<String, String>?, go: (String) -> Boolean, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var missing by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun submit() { if (input.isNotBlank() && !go(input)) missing = input }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Copy.goToPage) },
        text = {
            OutlinedTextField(
                value = input, onValueChange = { input = it; missing = null },
                modifier = Modifier.focusRequester(focus),
                singleLine = true,
                label = { Text(Copy.pageField) },
                isError = missing != null,
                supportingText = { Text(missing?.let(Copy::noSuchPage) ?: range?.let { Copy.pageRange(it.first, it.second) } ?: "") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
            )
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = input.isNotBlank()) { Text(Copy.goToPageAction) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Copy.cancel) } },
    )
}
