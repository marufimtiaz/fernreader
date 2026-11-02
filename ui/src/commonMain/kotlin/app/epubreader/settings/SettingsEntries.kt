package app.epubreader.settings

import app.epubreader.core.data.Flow
import app.epubreader.core.data.PageNumbers
import app.epubreader.core.data.OrientationLock
import app.epubreader.core.data.PageAnimation
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.SwipeDirection
import app.epubreader.core.data.VolumeKeys
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
    /** The reader's settings (M6 items and the page-number scope), current values. */
    val reader: ReaderSettings,
    /**
     * Changes them: `ReaderController.updateSettings`, so the open reader applies the change and stores it, exactly as
     * its own Reader settings sheet does (one source for both).
     */
    val onReader: ((ReaderSettings) -> ReaderSettings) -> Unit,
    val onBackUp: () -> Unit,
    val onRestore: () -> Unit,
    val onAbout: () -> Unit,
)

/**
 * The settings screen's content (F-100), grouped by topic. C-007: only settings of features that exist are listed.
 * M6's reader settings are here as well as in the reader's own Reader settings sheet; both edit the same
 * [ReaderSettings]: "Reading" (page numbers, page turning: animation, swipe direction, tap zones, volume keys), "Screen"
 * (fullscreen, keep screen on, orientation lock) and "Links" (external-link confirmation). Later milestones add theirs
 * here (M5 a "Search" section).
 */
/** F-031/F-032: the page-turn animation has no effect in Scroll mode (Auto still pages in portrait, C-004). */
val ReaderSettings.pageAnimationApplies: Boolean get() = flow != Flow.SCROLLED

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
        SettingItem.Choice("reader.pageScope", Copy.pageNumbers, PageNumbers.entries.map { it.name to Copy.pageNumbersName(it) },
            i.reader.pageNumbers.name, { v -> i.onReader { it.copy(pageNumbers = PageNumbers.valueOf(v)) } }),
        SettingItem.Choice("reader.pageAnimation", Copy.pageAnimation, listOf(PageAnimation.SLIDE.name to Copy.animSlide, PageAnimation.NONE.name to Copy.animNone),
            i.reader.pageAnimation.name, { v -> i.onReader { it.copy(pageAnimation = PageAnimation.valueOf(v)) } },
            summary = Copy.appliesToPageMode.takeUnless { i.reader.pageAnimationApplies }, enabled = i.reader.pageAnimationApplies),
        SettingItem.Choice("reader.swipeDirection", Copy.swipeDirection,
            listOf(SwipeDirection.AUTO.name to Copy.swipeAuto, SwipeDirection.LTR.name to Copy.swipeLtr, SwipeDirection.RTL.name to Copy.swipeRtl),
            i.reader.swipeDirection.name, { v -> i.onReader { it.copy(swipeDirection = SwipeDirection.valueOf(v)) } }),
        SettingItem.Toggle("reader.tapZones", Copy.tapZones, null, i.reader.tapZones) { v -> i.onReader { it.copy(tapZones = v) } },
        SettingItem.Choice("reader.volumeKeys", Copy.volumeKeys,
            listOf(VolumeKeys.OFF.name to Copy.volumeOff, VolumeKeys.ON.name to Copy.volumeOn, VolumeKeys.REVERSED.name to Copy.volumeReversed),
            i.reader.volumeKeys.name, { v -> i.onReader { it.copy(volumeKeys = VolumeKeys.valueOf(v)) } }, summary = Copy.volumeSummary(i.reader.volumeKeys)),
    )),
    SettingsSection(Copy.settingsScreen, listOf(
        SettingItem.Toggle("reader.fullscreen", Copy.fullscreen, null, i.reader.fullscreen) { v -> i.onReader { it.copy(fullscreen = v) } },
        SettingItem.Toggle("reader.keepScreenOn", Copy.keepScreenOn, null, i.reader.keepScreenOn) { v -> i.onReader { it.copy(keepScreenOn = v) } },
        SettingItem.Choice("reader.orientation", Copy.orientation,
            listOf(OrientationLock.SYSTEM.name to Copy.orientSystem, OrientationLock.PORTRAIT.name to Copy.orientPortrait, OrientationLock.LANDSCAPE.name to Copy.orientLandscape),
            i.reader.orientation.name, { v -> i.onReader { it.copy(orientation = OrientationLock.valueOf(v)) } }),
    )),
    SettingsSection(Copy.settingsLinks, listOf(
        SettingItem.Toggle("reader.confirmLinks", Copy.confirmLinks, null, i.reader.confirmExternalLinks) { v -> i.onReader { it.copy(confirmExternalLinks = v) } },
    )),
    SettingsSection(Copy.sectionBackup, listOf(
        SettingItem.Action("backup.save", Copy.backUpNow, Copy.backUpSummary, i.onBackUp),
        SettingItem.Action("backup.restore", Copy.restore, Copy.restoreSummary, i.onRestore),
    )),
    SettingsSection(Copy.about, listOf(
        SettingItem.Action("about", Copy.aboutApp, Copy.aboutSummary, i.onAbout),
    )),
)
