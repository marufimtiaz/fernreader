package app.epubreader.reader

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * C-111: the insets the reader's page and its furniture (the bookmark marker; C-112 took the page-number line away) are laid
 * out against.
 * Fixed: the bars' size whether they show or not, so the bars showing or hiding move nothing and draw over a band the page
 * keeps free. The platform provides them (Android: the bars ignoring visibility); null where it has none, and the visible
 * bars are used.
 */
val LocalPageInsets = staticCompositionLocalOf<WindowInsets?> { null }
