package app.epubreader.ui.theme

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * C-077: the library bar's sort and grid icons, which material-icons-core lacks. Drawn here in the same filled 24-unit
 * style as the Material icons next to them (our own shapes).
 */
object BarIcons {
    /** Three bars, shorter downwards. */
    val Sort: ImageVector = materialIcon("Fern.Sort") {
        materialPath {
            moveTo(3f, 6f); horizontalLineTo(21f); verticalLineTo(8f); horizontalLineTo(3f); close()
            moveTo(3f, 11f); horizontalLineTo(15f); verticalLineTo(13f); horizontalLineTo(3f); close()
            moveTo(3f, 16f); horizontalLineTo(9f); verticalLineTo(18f); horizontalLineTo(3f); close()
        }
    }

    /** Four squares. */
    val Grid: ImageVector = materialIcon("Fern.Grid") {
        materialPath {
            for ((x, y) in listOf(4f to 4f, 13f to 4f, 4f to 13f, 13f to 13f)) {
                moveTo(x, y); horizontalLineTo(x + 7f); verticalLineTo(y + 7f); horizontalLineTo(x); close()
            }
        }
    }
}
