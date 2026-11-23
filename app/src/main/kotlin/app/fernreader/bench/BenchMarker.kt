package app.fernreader.bench

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Visible start marker for screen-recorded bench actions (benchmark build only). */
object BenchMarker {
    private var on by mutableStateOf(false)
    fun toggle() { on = !on }

    @Composable fun Draw() { if (on) Box(Modifier.size(24.dp).background(Color.Black)) }
}
