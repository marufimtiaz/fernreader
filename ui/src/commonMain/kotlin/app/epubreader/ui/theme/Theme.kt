package app.epubreader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Our own palette: a deep green with warm paper neutrals (never copied from another reader). Containers and surfaces are
// set too, so Material's default violet never shows (drawer selection, chips, dialogs).
private val Light = lightColorScheme(
    primary = Color(0xFF2F5D50), onPrimary = Color.White, primaryContainer = Color(0xFFC6E5D8), onPrimaryContainer = Color(0xFF0E2B22),
    secondary = Color(0xFF8A6D3B), onSecondary = Color.White, secondaryContainer = Color(0xFFDDEBE4), onSecondaryContainer = Color(0xFF173129),
    tertiary = Color(0xFF8A6D3B), tertiaryContainer = Color(0xFFF2E3C6), onTertiaryContainer = Color(0xFF2C1F06),
    background = Color(0xFFFBFAF7), onBackground = Color(0xFF1B1C1A), surface = Color(0xFFFBFAF7), onSurface = Color(0xFF1B1C1A),
    surfaceVariant = Color(0xFFE2E4DE), onSurfaceVariant = Color(0xFF454944), outline = Color(0xFF757A73), outlineVariant = Color(0xFFC5C8C1),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F4EF), surfaceContainer = Color(0xFFEFEEE8),
    surfaceContainerHigh = Color(0xFFE9E8E2), surfaceContainerHighest = Color(0xFFE3E2DC),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8CC7B4), onPrimary = Color(0xFF00382B), primaryContainer = Color(0xFF184A3C), onPrimaryContainer = Color(0xFFC6E5D8),
    secondary = Color(0xFFD6B77E), onSecondary = Color(0xFF3E2D04), secondaryContainer = Color(0xFF2B3E37), onSecondaryContainer = Color(0xFFDDEBE4),
    tertiary = Color(0xFFD6B77E), tertiaryContainer = Color(0xFF574419), onTertiaryContainer = Color(0xFFF2E3C6),
    background = Color(0xFF151716), onBackground = Color(0xFFE3E3DF), surface = Color(0xFF151716), onSurface = Color(0xFFE3E3DF),
    surfaceVariant = Color(0xFF424842), onSurfaceVariant = Color(0xFFC2C8C0), outline = Color(0xFF8C928B), outlineVariant = Color(0xFF424842),
    surfaceContainerLowest = Color(0xFF101211), surfaceContainerLow = Color(0xFF1C1E1D), surfaceContainer = Color(0xFF202221),
    surfaceContainerHigh = Color(0xFF2A2C2B), surfaceContainerHighest = Color(0xFF353736),
)

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
