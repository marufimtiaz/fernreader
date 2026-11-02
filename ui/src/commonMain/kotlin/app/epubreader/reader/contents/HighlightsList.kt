package app.epubreader.reader.contents

import app.epubreader.ui.BarIcon
import app.epubreader.ui.IconLabel
import app.epubreader.ui.theme.AppIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.epubreader.core.data.Annotation
import app.epubreader.core.data.AnnotationStyle
import app.epubreader.reader.selection.HighlightPalette
import app.epubreader.ui.Copy

/**
 * The Highlights tab (F-062): one row per highlight or underline in book order: its colour mark, chapter, quoted text
 * (3 lines), note (2 lines, "View full note" expands it in place). Tap = jump; the icon removes it (Undo in the
 * confirmation). Live: [annotations] follows the database.
 */
@Composable
fun HighlightsList(
    annotations: List<Annotation>, onOpen: (Annotation) -> Unit, onRemove: (Annotation) -> Unit,
    /** C-083: the highlight's book page label while Book pages are shown, else null (no page). */
    bookPage: (Annotation) -> String? = { null },
) {
    if (annotations.isEmpty()) { EmptyTab(Copy.noHighlights); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(annotations, key = { it.id }) { a ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(a) }.padding(start = 16.dp, end = 4.dp)) {
                Mark(a, Modifier.padding(top = 16.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    val chapter = a.chapter.ifBlank { Copy.untitledEntry }
                    Text(bookPage(a)?.let { "$chapter · ${Copy.bookPage(it)}" } ?: chapter, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(a.text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp))
                    a.note?.let { note -> Note(note) }
                }
                BarIcon(AppIcons.Delete, Copy.removeHighlightFromList, Modifier.padding(top = 4.dp)) { onRemove(a) }
            }
            HorizontalDivider()
        }
    }
}

/** A filled dot for a highlight, a short thick bar for an underline, in the annotation's colour. */
@Composable
private fun Mark(a: Annotation, modifier: Modifier) {
    val color = HighlightPalette.colors[a.color] ?: MaterialTheme.colorScheme.primary
    val underline = a.style == AnnotationStyle.UNDERLINE
    Box(modifier.size(20.dp).semantics { contentDescription = Copy.annotationMark(a.color, underline) }, contentAlignment = Alignment.Center) {
        if (underline) Box(Modifier.padding(top = 10.dp).width(18.dp).height(4.dp).background(color, RoundedCornerShape(2.dp)))
        else Box(Modifier.size(16.dp).background(color, CircleShape))
    }
}

@Composable
private fun Note(note: String) {
    var full by rememberSaveable(note) { mutableStateOf(false) }
    var cut by remember(note) { mutableStateOf(false) }
    Text(
        note, maxLines = if (full) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
        onTextLayout = { if (!full) cut = it.hasVisualOverflow },
    )
    // Offered only when the two-line preview does not show the whole note.
    if (full || cut) TextButton(onClick = { full = !full }) { if (full) IconLabel(AppIcons.ShowLess, Copy.hideFullNote) else IconLabel(AppIcons.ShowMore, Copy.viewFullNote) }
}
