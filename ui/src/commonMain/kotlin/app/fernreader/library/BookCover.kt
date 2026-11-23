package app.fernreader.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import coil3.compose.AsyncImage

/** Our own placeholder palette (muted, readable with white text). */
private val PLACEHOLDER = listOf(
    Color(0xFF3D5A80), Color(0xFF6B4E71), Color(0xFF2F6F5E), Color(0xFF8C5A3C),
    Color(0xFF4A5568), Color(0xFF7A3E48), Color(0xFF2C6E8F), Color(0xFF5B6B2F),
)

/** Stable colour for a title (F-021 placeholder). */
fun placeholderColorIndex(title: String): Int = (title.hashCode() % PLACEHOLDER.size + PLACEHOLDER.size) % PLACEHOLDER.size

/** F-021: the thumbnail from the cache, or a generated placeholder showing the title. `thumb` "" or null = none. */
@Composable
fun BookCover(title: String, thumb: String?, modifier: Modifier = Modifier, small: Boolean = false) {
    val shape = RoundedCornerShape(if (small) 3.dp else 6.dp)
    if (!thumb.isNullOrEmpty()) {
        AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(shape))
        return
    }
    Box(modifier.clip(shape).background(PLACEHOLDER[placeholderColorIndex(title)]).padding(if (small) 3.dp else 8.dp), contentAlignment = Alignment.Center) {
        Text(
            title, color = Color.White, textAlign = TextAlign.Center, maxLines = if (small) 3 else 5, overflow = TextOverflow.Ellipsis,
            style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleSmall,
        )
    }
}

/** Drops decoded covers held in memory (the reader needs the room; the thumbnail files stay). */
fun releaseCoverMemory(context: coil3.PlatformContext) { coil3.SingletonImageLoader.get(context).memoryCache?.clear() }
