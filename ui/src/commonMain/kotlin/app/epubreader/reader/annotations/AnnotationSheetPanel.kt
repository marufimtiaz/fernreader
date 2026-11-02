package app.epubreader.reader.annotations

import app.epubreader.ui.IconLabel
import app.epubreader.ui.theme.AppIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.epubreader.core.data.AnnotationStyle
import app.epubreader.reader.AnnotationSheet
import app.epubreader.reader.selection.ColorSwatches
import app.epubreader.reader.selection.HighlightPalette
import app.epubreader.ui.Copy

/**
 * F-061: the sheet of a tapped highlight, anchored to the bottom over the page like the display panel (the page stays
 * visible above, so a colour or style change shows at once). Quoted text, the six colours, Highlight | Underline, the
 * note (plain text, several lines; saved when the sheet closes), Remove and Done. Lifted above the keyboard.
 */
@Composable
fun AnnotationSheetPanel(
    sheet: AnnotationSheet,
    onColor: (String) -> Unit,
    onStyle: (String) -> Unit,
    onNote: (String) -> Unit,
    onRemove: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.fillMaxWidth().imePadding().semantics { contentDescription = Copy.highlightSheet },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier.navigationBarsPadding().heightIn(max = 520.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The quote, with a bar in the annotation's colour.
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(HighlightPalette.colors[sheet.color] ?: MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(12.dp))
                Text(sheet.text, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            ColorSwatches(sheet.color, onColor, Modifier.align(Alignment.CenterHorizontally))
            val styles = listOf(AnnotationStyle.HIGHLIGHT to Copy.styleHighlight, AnnotationStyle.UNDERLINE to Copy.styleUnderline)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                styles.forEachIndexed { i, (v, label) ->
                    SegmentedButton(selected = v == sheet.style, onClick = { onStyle(v) }, shape = SegmentedButtonDefaults.itemShape(i, styles.size)) {
                        Text(label, maxLines = 1)
                    }
                }
            }
            // Local text state (cursor and IME composition stay smooth); every edit goes to the controller's draft.
            var field by remember(sheet.id) { mutableStateOf(TextFieldValue(sheet.note)) }
            OutlinedTextField(
                value = field,
                onValueChange = { field = it; onNote(it.text) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(Copy.note) },
                placeholder = { Text(Copy.notePlaceholder) },
                minLines = 2, maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRemove, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { IconLabel(AppIcons.Delete, Copy.removeHighlight) }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = onDone) { IconLabel(AppIcons.Check, Copy.done) }
            }
        }
    }
}
