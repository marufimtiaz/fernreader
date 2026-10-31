package app.epubreader.settings

import app.epubreader.core.data.IndicatorScope
import app.epubreader.ui.Copy

/** Everything the settings screen shows and changes, gathered by the app. */
class SettingsInputs(
    val theme: AppThemeMode,
    val onTheme: (AppThemeMode) -> Unit,
    val folderView: Boolean,
    val onFolderView: (Boolean) -> Unit,
    /** F-011: null = everywhere. */
    val folder: String?,
    val onEverywhere: () -> Unit,
    /** Opens the system folder picker. */
    val onPickFolder: () -> Unit,
    val hiddenCount: Int,
    val onHiddenBooks: () -> Unit,
    val pageScope: IndicatorScope,
    val onPageScope: (IndicatorScope) -> Unit,
    val onBackUp: () -> Unit,
    val onRestore: () -> Unit,
    val onAbout: () -> Unit,
)

/**
 * The settings screen's content (F-100), grouped by topic. C-007: only settings of features that exist are listed.
 * Later milestones add theirs here: M5 a "Search" section, M6 "Reading" input and screen items (tap zones, volume
 * keys, external-link confirmation, keep screen on, fullscreen, orientation lock).
 */
fun settingsSections(i: SettingsInputs): List<SettingsSection> = listOf(
    SettingsSection(Copy.sectionAppearance, listOf(
        SettingItem.Choice("app.theme", Copy.appTheme,
            listOf(AppThemeMode.SYSTEM.name to Copy.themeSystem, AppThemeMode.LIGHT.name to Copy.themeLight, AppThemeMode.DARK.name to Copy.themeDark),
            i.theme.name, { v -> i.onTheme(AppThemeMode.valueOf(v)) }),
    )),
    SettingsSection(Copy.sectionLibrary, listOf(
        SettingItem.Choice("library.scope", Copy.discovery, listOf("everywhere" to Copy.everywhere, "folder" to Copy.oneFolder), if (i.folder == null) "everywhere" else "folder",
            { v -> if (v == "everywhere") i.onEverywhere() else i.onPickFolder() },
            summary = i.folder?.let { Copy.onlyIn(Copy.displayFolder(it)) } ?: Copy.everywhere),
        SettingItem.Toggle("library.folderView", Copy.folderView, Copy.folderViewSummary, i.folderView, i.onFolderView),
        SettingItem.Action("library.hidden", Copy.hiddenBooks, Copy.hiddenSummary(i.hiddenCount), i.onHiddenBooks),
    )),
    SettingsSection(Copy.sectionReading, listOf(
        SettingItem.Choice("reader.pageScope", Copy.pageNumbers, listOf(IndicatorScope.BOOK.name to Copy.scopeBook, IndicatorScope.CHAPTER.name to Copy.scopeChapter),
            i.pageScope.name, { v -> i.onPageScope(IndicatorScope.valueOf(v)) }),
    )),
    SettingsSection(Copy.sectionBackup, listOf(
        SettingItem.Action("backup.save", Copy.backUpNow, Copy.backUpSummary, i.onBackUp),
        SettingItem.Action("backup.restore", Copy.restore, Copy.restoreSummary, i.onRestore),
    )),
    SettingsSection(Copy.about, listOf(
        SettingItem.Action("about", Copy.aboutApp, Copy.aboutSummary, i.onAbout),
    )),
)
