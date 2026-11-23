package app.fernreader.reader.display

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.fernreader.core.data.ReadingThemeId

/**
 * Reading themes (F-074), our own palette. The page colours are mirrored in reader-web/src/reader/styles.ts
 * (ReadingThemeTest checks they agree). The reader chrome and the display panel use [colorScheme], so the panel
 * follows the active theme. System (C-105) has no colours of its own: [of] resolves it to Light or Black.
 */
data class ReadingTheme(
    val id: ReadingThemeId,
    val background: Color,
    val text: Color,
    val link: Color,
    val isLight: Boolean,
    private val surface: Color,
    private val surfaceHigh: Color,
) {
    fun colorScheme(): ColorScheme = if (isLight) lightColorScheme(
        primary = link, onPrimary = background, secondaryContainer = surfaceHigh, onSecondaryContainer = text,
        background = background, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = surfaceHigh, onSurfaceVariant = text.copy(alpha = 0.72f),
        surfaceContainer = surface, surfaceContainerHigh = surfaceHigh, surfaceContainerHighest = surfaceHigh,
        outline = text.copy(alpha = 0.35f), outlineVariant = text.copy(alpha = 0.16f),
    ) else darkColorScheme(
        primary = link, onPrimary = background, secondaryContainer = surfaceHigh, onSecondaryContainer = text,
        background = background, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = surfaceHigh, onSurfaceVariant = text.copy(alpha = 0.72f),
        surfaceContainer = surface, surfaceContainerHigh = surfaceHigh, surfaceContainerHighest = surfaceHigh,
        outline = text.copy(alpha = 0.35f), outlineVariant = text.copy(alpha = 0.16f),
    )

    companion object {
        val Light = ReadingTheme(ReadingThemeId.LIGHT, Color(0xFFFBFAF7), Color(0xFF1F1D1A), Color(0xFF2F5D50), true, Color(0xFFF2F0EA), Color(0xFFE7E3DA))
        val Dark = ReadingTheme(ReadingThemeId.DARK, Color(0xFF212427), Color(0xFFDAD6CE), Color(0xFF8CC7B4), false, Color(0xFF2A2E32), Color(0xFF353A3F))
        val Black = ReadingTheme(ReadingThemeId.BLACK, Color(0xFF000000), Color(0xFFC4C0B8), Color(0xFF7FB8A6), false, Color(0xFF0E0E0E), Color(0xFF1C1C1C))
        val Warm = ReadingTheme(ReadingThemeId.WARM, Color(0xFFF4E9D3), Color(0xFF3A2E21), Color(0xFF7A4E1E), true, Color(0xFFECDFC4), Color(0xFFE2D2B2))

        /** The theme the page shows for [id]; System (C-105) is Light or Black as the phone's dark mode says. */
        fun of(id: ReadingThemeId, systemDark: Boolean): ReadingTheme = when (id.resolve(systemDark)) {
            ReadingThemeId.SYSTEM, ReadingThemeId.LIGHT -> Light
            ReadingThemeId.DARK -> Dark
            ReadingThemeId.BLACK -> Black
            ReadingThemeId.WARM -> Warm
        }
    }
}
