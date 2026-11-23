package app.fernreader.devtools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.fernreader.core.data.SourceRef
import app.fernreader.library.BookDetailsPage
import app.fernreader.library.ConfirmDialog
import app.fernreader.reader.EventProbe
import app.fernreader.reader.display.ReadingTheme
import app.fernreader.settings.AboutScreen
import app.fernreader.settings.AppThemeMode
import app.fernreader.settings.HiddenBooksScreen
import app.fernreader.settings.LicenceBlock
import app.fernreader.settings.LicenceText
import app.fernreader.settings.LicencesScreen
import app.fernreader.settings.SettingsInputs
import app.fernreader.settings.SettingsScreen
import app.fernreader.settings.settingsSections
import app.fernreader.ui.App
import app.fernreader.ui.Copy
import app.fernreader.ui.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)
private fun formatDate(ms: Long) = dates.format(Date(ms))

/** The app's root as MainActivity composes it, with a placeholder for the WebView. */
@Composable
fun DevApp(g: DevGraph) {
    val mode by g.prefs.theme.collectAsState()
    val dark = when (mode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    App(
        dark = dark, webViewTooOld = false, nav = g.nav, library = g.libraryState, reader = g.reader, probe = EventProbe.None,
        readerSurface = { ReaderPlaceholder(g) },
        onOpenPicker = {}, onGrantAccess = {}, onLeave = {},
        onOpen = { g.nav.push(Route.Reader); g.reader.open(it) },
        onRescan = {}, onForget = { ref: SourceRef -> g.scope.launch { g.library.forget(ref) } },
        onBack = { goBack(g) },
        pages = { DevPages(it, g) },
        formatDate = ::formatDate,
    )
}

/** Back as the system key does it: the reader's panels first, then the library's views, then the route. */
fun goBack(g: DevGraph) {
    if (g.nav.top == Route.Reader) {
        if (!g.reader.backInside()) { g.reader.close(); g.nav.pop() }
    } else if (g.nav.top != Route.Library || !g.libraryState.back()) g.nav.pop()
}

@Composable
private fun ReaderPlaceholder(g: DevGraph) {
    val settings by g.reader.settings.collectAsState()
    val systemDark by g.reader.systemDark.collectAsState()
    val theme = ReadingTheme.of(settings.theme, systemDark)
    Box(Modifier.fillMaxSize().background(theme.background).clickable { g.tapPage() }, contentAlignment = Alignment.Center) {
        Text("Reader surface: run `npm run dev` in reader-web", color = theme.text)
    }
}

/** AppPages.kt without what needs Android (system pickers, backup). */
@Composable
private fun DevPages(route: Route, g: DevGraph) {
    val back = { g.nav.pop(); Unit }
    when (route) {
        Route.Settings -> {
            val ui by g.settingsState.ui.collectAsState()
            val theme by g.prefs.theme.collectAsState()
            val folderView by g.prefs.folderView.collectAsState()
            val rs by g.reader.settings.collectAsState()
            SettingsScreen(
                settingsSections(SettingsInputs(
                    theme = theme, onTheme = g.prefs::setTheme,
                    folderView = folderView, onFolderView = g.prefs::setFolderView,
                    folder = ui.folder, onEverywhere = g.settingsState::useEverywhere, onPickFolder = { g.settingsState.chooseFolder("/storage/emulated/0/Download") },
                    hiddenCount = ui.hidden.size, onHiddenBooks = { g.nav.push(Route.HiddenBooks) },
                    reader = rs, onReader = g.reader::updateSettings,
                    onBackUp = {}, onRestore = {}, onAbout = { g.nav.push(Route.About) },
                )),
                onBack = back,
            )
            ui.outside?.let { o ->
                ConfirmDialog(Copy.outsideTitle, Copy.outsideBody(o.bookIds.size, Copy.displayFolder(o.folder)), Copy.hide,
                    onConfirm = g.settingsState::hideOutside, onCancel = g.settingsState::keepOutside)
            }
        }
        Route.HiddenBooks -> {
            val ui by g.settingsState.ui.collectAsState()
            HiddenBooksScreen(ui.hidden, onUnhide = g.settingsState::unhide, onBack = back)
        }
        Route.About -> AboutScreen("dev", onLicences = { g.nav.push(Route.Licences) }, onBack = back)
        Route.Licences -> {
            val blocks by produceState(emptyList<LicenceBlock>()) {
                value = withContext(Dispatchers.IO) { g.licencesFile?.takeIf { it.exists() }?.let { LicenceText.parse(it.readText()) }.orEmpty() }
            }
            LicencesScreen(blocks, onBack = back)
        }
        is Route.BookDetails -> BookDetailsPage(route.bookId, g.library, ::formatDate, onBack = back)
        else -> Unit
    }
}
