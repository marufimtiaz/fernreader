package app.fernreader.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * C-077 / C-086: an icon-only bar button (48 dp target): [label] is its content description and its long-press tooltip.
 * The icon takes the bar's content colour (light, sepia and dark themes) unless [tint] is given.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarIcon(icon: ImageVector, label: String, modifier: Modifier = Modifier, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    TooltipBox(TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, modifier) {
            Icon(icon, label, tint = if (tint == Color.Unspecified) LocalContentColor.current else tint)
        }
    }
}

/** C-086: the content of a labelled button in a panel: an icon in front of the label (the label stays the accessible text). */
@Composable
fun IconLabel(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(label)
    }
}
