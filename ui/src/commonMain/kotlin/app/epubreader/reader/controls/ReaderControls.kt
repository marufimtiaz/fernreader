package app.epubreader.reader.controls

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.epubreader.ui.Copy

/** Actions of the controls overlay (F-033). The reader controller supplies them; the overlay owns no state but the slider drag. */
class ControlActions(
    val back: () -> Unit,
    val search: () -> Unit,
    val bookmark: () -> Unit,
    val contents: () -> Unit,
    val display: () -> Unit,
    val settings: () -> Unit,
    val seek: (Double) -> Unit,
)

/**
 * Top half of the controls: back, chapter title (book title beneath), search, bookmark, contents. Rounded where it
 * meets the page; floats over the page (it never changes the page's size).
 */
@Composable
fun ReaderTopBar(chapter: String, bookTitle: String, tocEnabled: Boolean, a: ControlActions, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = a.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, Copy.back) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(chapter, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                if (bookTitle.isNotBlank() && bookTitle != chapter) {
                    Text(bookTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = a.search) { Icon(Icons.Filled.Search, Copy.search) }
            IconButton(onClick = a.bookmark) { Icon(BookmarkIcon, Copy.addBookmark) }
            IconButton(onClick = a.contents, enabled = tocEnabled) { Icon(Icons.AutoMirrored.Filled.List, Copy.contents) }
        }
    }
}

/** Bottom half: page label, seek slider, display and reader settings. */
@Composable
fun ReaderBottomBar(pageLabel: String?, fraction: Double, a: ControlActions, modifier: Modifier = Modifier) {
    var drag by remember { mutableStateOf<Float?>(null) }
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Text(pageLabel ?: "", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = drag ?: fraction.toFloat(), onValueChange = { drag = it },
                    onValueChangeFinished = { drag?.let { a.seek(it.toDouble()) }; drag = null },
                    modifier = Modifier.weight(1f).semantics { contentDescription = Copy.seek },
                )
                IconButton(onClick = a.display, modifier = Modifier.semantics { contentDescription = Copy.display }) {
                    Text("Aa", style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onClick = a.settings) { Icon(Icons.Filled.Settings, Copy.readerSettings) }
            }
        }
    }
}

/** Outline bookmark ribbon (not in the core icon set). */
private val BookmarkIcon: ImageVector = ImageVector.Builder("Bookmark", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
        moveTo(17f, 3f); horizontalLineTo(7f)
        curveTo(5.9f, 3f, 5f, 3.9f, 5f, 5f)
        verticalLineTo(21f); lineTo(12f, 18f); lineTo(19f, 21f); verticalLineTo(5f)
        curveTo(19f, 3.9f, 18.1f, 3f, 17f, 3f); close()
        moveTo(17f, 18f); lineTo(12f, 15.8f); lineTo(7f, 18f); verticalLineTo(5f); horizontalLineTo(17f); close()
    }
}.build()
