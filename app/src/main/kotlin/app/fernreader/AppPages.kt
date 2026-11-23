package app.fernreader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import app.fernreader.library.BookDetailsPage
import app.fernreader.reader.ReaderController
import app.fernreader.settings.AboutScreen
import app.fernreader.settings.HiddenBooksScreen
import app.fernreader.settings.LicenceText
import app.fernreader.settings.LicencesScreen
import app.fernreader.settings.SettingsInputs
import app.fernreader.settings.SettingsScreen
import app.fernreader.settings.SettingsState
import app.fernreader.settings.settingsSections
import app.fernreader.ui.Navigator
import app.fernreader.ui.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The system pickers and file work the settings pages start (owned by MainActivity). */
class PageActions(val pickFolder: () -> Unit, val backUp: () -> Unit, val restore: () -> Unit, val date: (Long) -> String)

/** M7 pages over the library (F-100, F-025 un-hide, F-092 about, F-015 details). */
@Composable
fun AppPages(route: Route, nav: Navigator, graph: AppGraph, settings: SettingsState, reader: ReaderController, actions: PageActions, version: String,
             readAsset: (String) -> String) {
    val back = { nav.pop(); Unit }
    when (route) {
        Route.Settings -> {
            val ui by settings.ui.collectAsState()
            val theme by graph.prefs.theme.collectAsState()
            val folderView by graph.prefs.folderView.collectAsState()
            val rs by reader.settings.collectAsState()
            val saving by reader.batterySaving.collectAsState() // C-116
            SettingsScreen(
                settingsSections(SettingsInputs(
                    theme = theme, onTheme = graph.prefs::setTheme,
                    folderView = folderView, onFolderView = graph.prefs::setFolderView,
                    folder = ui.folder, onEverywhere = settings::useEverywhere, onPickFolder = actions.pickFolder,
                    hiddenCount = ui.hidden.size, onHiddenBooks = { nav.push(Route.HiddenBooks) },
                    reader = rs, onReader = reader::updateSettings, batterySaving = saving,
                    onBackUp = actions.backUp, onRestore = actions.restore, onAbout = { nav.push(Route.About) },
                )),
                onBack = back,
            )
            ui.outside?.let { o ->
                app.fernreader.library.ConfirmDialog(
                    app.fernreader.ui.Copy.outsideTitle, app.fernreader.ui.Copy.outsideBody(o.bookIds.size, app.fernreader.ui.Copy.displayFolder(o.folder)),
                    app.fernreader.ui.Copy.hide, onConfirm = settings::hideOutside, onCancel = settings::keepOutside,
                )
            }
        }
        Route.HiddenBooks -> {
            val ui by settings.ui.collectAsState()
            HiddenBooksScreen(ui.hidden, onUnhide = settings::unhide, onBack = back)
        }
        Route.About -> AboutScreen(version, onLicences = { nav.push(Route.Licences) }, onBack = back)
        Route.Licences -> {
            val blocks by produceState(emptyList<app.fernreader.settings.LicenceBlock>()) {
                value = withContext(Dispatchers.IO) { LicenceText.parse(readAsset("licenses.md")) }
            }
            LicencesScreen(blocks, onBack = back)
        }
        is Route.BookDetails -> BookDetailsPage(route.bookId, graph.library, actions.date, onBack = back)
        else -> Unit
    }
}
