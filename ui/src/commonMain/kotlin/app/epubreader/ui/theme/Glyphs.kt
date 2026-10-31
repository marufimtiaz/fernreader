package app.epubreader.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Our own line icons (24-unit grid, 2-unit strokes), drawn here so the app needs no icon library. Tinted by Icon().
 */
object Glyphs {
    private fun glyph(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        ).build()

    val Menu = glyph("menu") { moveTo(4f, 7f); lineTo(20f, 7f); moveTo(4f, 12f); lineTo(20f, 12f); moveTo(4f, 17f); lineTo(20f, 17f) }
    val Close = glyph("close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val Back = glyph("back") { moveTo(19f, 12f); lineTo(5f, 12f); moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f) }
    val Check = glyph("check") { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) }
    val Plus = glyph("plus") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
    val Books = glyph("books") {
        moveTo(5f, 4f); lineTo(5f, 20f); moveTo(9f, 4f); lineTo(9f, 20f)
        moveTo(13f, 5f); lineTo(17f, 4f); lineTo(20f, 19f); lineTo(16f, 20f); close()
    }
    val Shelf = glyph("collection") {
        moveTo(4f, 8f); lineTo(20f, 8f); lineTo(20f, 19f); lineTo(4f, 19f); close()
        moveTo(6f, 5f); lineTo(18f, 5f)
    }
    val Folder = glyph("folder") {
        moveTo(3f, 7f); lineTo(3f, 18f); lineTo(21f, 18f); lineTo(21f, 9f); lineTo(12f, 9f); lineTo(10f, 6f); lineTo(3f, 6f); close()
    }
    val Settings = glyph("settings") {
        moveTo(4f, 7f); lineTo(20f, 7f); moveTo(4f, 12f); lineTo(20f, 12f); moveTo(4f, 17f); lineTo(20f, 17f)
        moveTo(9f, 5f); lineTo(9f, 9f); moveTo(15f, 10f); lineTo(15f, 14f); moveTo(7f, 15f); lineTo(7f, 19f)
    }
    val Info = glyph("info") {
        moveTo(12f, 3f); arcTo(9f, 9f, 0f, true, true, 11.99f, 3f)
        moveTo(12f, 11f); lineTo(12f, 17f); moveTo(12f, 7.5f); lineTo(12f, 7.6f)
    }
    val More = glyph("more") { moveTo(12f, 5f); lineTo(12f, 5.1f); moveTo(12f, 12f); lineTo(12f, 12.1f); moveTo(12f, 19f); lineTo(12f, 19.1f) }
    val ChevronRight = glyph("expand") { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }
    val ChevronDown = glyph("collapse") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }
    val Trash = glyph("delete") {
        moveTo(4f, 7f); lineTo(20f, 7f); moveTo(9f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 7f)
        moveTo(6f, 7f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 7f)
    }
    val SelectAll = glyph("select-all") {
        moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close()
        moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 9f)
    }
}
