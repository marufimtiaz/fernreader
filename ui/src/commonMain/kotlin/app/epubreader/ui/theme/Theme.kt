package app.epubreader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Our own palette (placeholder values until the design pass in M3; never copied from another reader).
private val Light = lightColorScheme(primary = Color(0xFF2F5D50), secondary = Color(0xFF8A6D3B), background = Color(0xFFFBFAF7))
private val Dark = darkColorScheme(primary = Color(0xFF8CC7B4), secondary = Color(0xFFD6B77E), background = Color(0xFF151716))

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
