package app.epubreader.reader.selection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.epubreader.core.data.HighlightColors
import app.epubreader.ui.Copy

/**
 * The six highlight colours (F-060) by HighlightColors id. Same values as reader-web/src/reader/annotations.ts
 * HIGHLIGHT_COLORS (the page draws them at 0.3 opacity; the swatches show them solid).
 */
object HighlightPalette {
    val colors: Map<String, Color> = mapOf(
        HighlightColors.YELLOW to Color(0xFFFDD835),
        HighlightColors.GREEN to Color(0xFF66BB6A),
        HighlightColors.BLUE to Color(0xFF42A5F5),
        HighlightColors.PINK to Color(0xFFF06292),
        HighlightColors.ORANGE to Color(0xFFFFA726),
        HighlightColors.PURPLE to Color(0xFFAB47BC),
    )
}

/**
 * Shown after Highlight or Underline from the selection menu: the new annotation already has [current]; one tap on
 * another swatch recolours it. Floats above the page's bottom margin, coloured by the reading theme.
 */
@Composable
fun HighlightColorRow(current: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier.navigationBarsPadding().padding(bottom = 40.dp).semantics { contentDescription = Copy.highlightColors },
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (id in HighlightColors.ALL) {
                val selected = id == current
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.RadioButton) { onPick(id) }
                        .semantics { this.selected = selected; contentDescription = Copy.highlightColorName(id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(34.dp).clip(CircleShape)
                            .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(Modifier.size(if (selected) 26.dp else 32.dp), shape = CircleShape, color = HighlightPalette.colors.getValue(id)) {}
                    }
                }
            }
        }
    }
}
