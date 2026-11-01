package app.epubreader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import app.epubreader.core.data.BookDetails
import app.epubreader.library.BookDetailsScreen
import app.epubreader.reader.ReaderController
import app.epubreader.settings.AboutScreen
import app.epubreader.settings.HiddenBooksScreen
import app.epubreader.settings.LicenceText
import app.epubreader.settings.LicencesScreen
import app.epubreader.settings.SettingsInputs
import app.epubreader.settings.SettingsScreen
import app.epubreader.settings.SettingsState
import app.epubreader.settings.settingsSections
import app.epubreader.ui.Navigator
import app.epubreader.ui.Route
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
            SettingsScreen(
                settingsSections(SettingsInputs(
                    theme = theme, onTheme = graph.prefs::setTheme,
                    folderView = folderView, onFolderView = graph.prefs::setFolderView,
                    folder = ui.folder, onEverywhere = settings::useEverywhere, onPickFolder = actions.pickFolder,
                    hiddenCount = ui.hidden.size, onHiddenBooks = { nav.push(Route.HiddenBooks) },
                    reader = rs, onReader = reader::updateSettings,
                    onBackUp = actions.backUp, onRestore = actions.restore, onAbout = { nav.push(Route.About) },
                )),
                onBack = back,
            )
            ui.outside?.let { o ->
                app.epubreader.library.ConfirmDialog(
                    app.epubreader.ui.Copy.outsideTitle, app.epubreader.ui.Copy.outsideBody(o.bookIds.size, app.epubreader.ui.Copy.displayFolder(o.folder)),
                    app.epubreader.ui.Copy.hide, onConfirm = settings::hideOutside, onCancel = settings::keepOutside,
                )
            }
        }
        Route.HiddenBooks -> {
            val ui by settings.ui.collectAsState()
            HiddenBooksScreen(ui.hidden, onUnhide = settings::unhide, onBack = back)
        }
        Route.About -> AboutScreen(version, onLicences = { nav.push(Route.Licences) }, onBack = back)
        Route.Licences -> {
            val blocks by produceState(emptyList<app.epubreader.settings.LicenceBlock>()) {
                value = withContext(Dispatchers.IO) { LicenceText.parse(readAsset("licenses.md")) }
            }
            LicencesScreen(blocks, onBack = back)
        }
        is Route.BookDetails -> {
            val d by produceState<BookDetails?>(null, route.bookId) { value = graph.library.details(route.bookId) }
            BookDetailsScreen(d, actions.date, onBack = back)
        }
        else -> Unit
    }
}
