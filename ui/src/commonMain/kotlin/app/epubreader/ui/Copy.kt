package app.epubreader.ui

import app.epubreader.reader.OpenFailure
import app.epubreader.reader.ReaderError

/** All user-facing text (our own wording). Moves to Compose resources when the app is translated. */
object Copy {
    const val appTitle = "Books"
    const val appName = "FernReader"
    const val allBooks = "All books"
    const val folders = "Folders"
    const val collections = "Collections"
    const val newCollection = "New collection"
    const val renameCollection = "Rename collection"
    const val deleteCollection = "Delete collection"
    fun deleteCollectionTitle(name: String) = "Delete “$name”?"
    const val deleteCollectionBody = "The collection is deleted. Its books stay in your library."
    const val collectionName = "Name"
    const val nameTaken = "A collection with this name already exists"
    const val save = "Save"
    const val delete = "Delete"
    const val remove = "Remove"
    const val settings = "Settings"
    const val about = "About"
    const val openMenu = "Open navigation"
    const val endSelection = "End selection"
    const val selectAll = "Select all"
    fun selectedCount(n: Int) = "$n selected"
    const val addToCollection = "Add to collection"
    const val addToCollectionShort = "Add to…"
    const val removeFromCollection = "Remove from collection"
    const val addBooks = "Add books"
    fun addCount(n: Int) = if (n == 0) "Add" else "Add $n"
    const val allBooksAlreadyIn = "Every book in the library is already in this collection."
    const val emptyCollection = "No books in this collection yet."
    const val emptyFolder = "No books in this folder."
    const val noFolders = "No folders with books."
    /** "41", or "41 · 1 here" when some of them are in subfolders. */
    fun folderCount(direct: Int, total: Int) = if (direct == 0 || direct == total) "$total" else "$total · $direct here"
    const val includeSubfolders = "Subfolders"
    const val expand = "Show subfolders"
    const val collapse = "Hide subfolders"
    fun expandFolder(name: String) = "Show subfolders of $name"
    fun collapseFolder(name: String) = "Hide subfolders of $name"
    /** C-074: the drawer's link to the full folder tree when its folder list is long. */
    const val showAllFolders = "Show all folders"
    /** A storage volume row shows as the device's storage, other rows by their own name. */
    fun folderName(name: String) = when {
        Regex("^/storage/emulated/\\d+$").matches(name) -> "Device storage"
        name.startsWith("/storage/") -> "Storage " + name.removePrefix("/storage/")
        else -> name
    }
    fun deleteTitle(n: Int) = if (n == 1) "Remove this book?" else "Remove $n books?"
    const val deleteLibraryOnlyBody = "The book leaves the library and stays hidden on later scans. Its file stays on the device. You can show it again in Settings → Hidden books."
    fun deleteFilesBody(files: Int) = if (files == 1) "The book's file is deleted from the device." else "The books' $files files are deleted from the device."
    fun alsoDeleteFiles(files: Int) = if (files == 1) "Also delete the file from the device" else "Also delete the files from the device ($files)"
    const val deleteFiles = "Delete"
    fun books(n: Int) = if (n == 1) "1 book" else "$n books"
    fun deletedMessage(n: Int, files: Boolean, failed: Int) = when {
        !files -> "Removed ${books(n)} from the library"
        failed > 0 -> "Deleted ${books(n)}. ${if (failed == 1) "1 file" else "$failed files"} couldn't be deleted, so ${if (failed == 1) "its book is" else "their books are"} hidden instead."
        else -> "Deleted ${books(n)} and ${if (n == 1) "its file" else "their files"}"
    }
    fun added(n: Int, collection: String) = "Added ${books(n)} to “$collection”"
    const val openBook = "Open book"
    const val libraryEmpty = "No books found on this device yet. Rescan after adding EPUB files, or open one directly."
    const val looking = "Looking for books…"
    const val searchHint = "Search title or author"
    const val listView = "List"
    const val gridView = "Grid"
    const val more = "More"
    const val rescan = "Rescan"
    const val details = "Details"
    const val dismiss = "Dismiss"
    const val unknownAuthor = "Unknown author"
    const val failuresTitle = "Files that couldn't be opened"
    fun noMatch(q: String) = "No books match “${q.trim()}”."
    fun failuresNotice(n: Int) = if (n == 1) "1 file couldn't be opened." else "$n files couldn't be opened."
    fun sortName(s: app.epubreader.library.LibrarySort) = when (s) {
        app.epubreader.library.LibrarySort.RECENT -> "Recently read"
        app.epubreader.library.LibrarySort.ADDED -> "Date added"
        app.epubreader.library.LibrarySort.TITLE -> "Title"
        app.epubreader.library.LibrarySort.AUTHOR -> "Author"
    }
    fun sortLabel(s: app.epubreader.library.LibrarySort) = "Sort: " + sortName(s)
    fun failReason(name: String) = when (name) {
        "NOT_A_ZIP" -> "Not an EPUB file (not a zip archive)"
        "DAMAGED_ARCHIVE" -> "Damaged or incomplete archive"
        "NO_PACKAGE" -> "No book package found"
        "BROKEN_PACKAGE" -> "Broken book package"
        "NOTHING_READABLE" -> "Nothing readable inside"
        else -> "Can't be opened"
    }
    /** Shared-storage folders without the device prefix ("/storage/emulated/0/Download" → "Download"). */
    fun displayFolder(folder: String) = folder.replace(Regex("^/storage/emulated/\\d+/?"), "").ifEmpty { "/" }
    const val onboardingTitle = "Find your books"
    const val onboardingBody = "To list the EPUB books on this device automatically, the app needs permission to read all files. Books are only read, never uploaded or changed."
    const val onboardingGrant = "Allow access"
    const val onboardingPicker = "Open a single book"
    const val onboardingLeave = "Leave"
    const val back = "Back"
    const val display = "Display"
    const val seek = "Position in book"
    /** C-073: what tapping the book title in the reader's top bar does (accessibility action label). */
    const val bookDetails = "Show title and author"
    fun authorsLine(authors: List<String>) = authors.joinToString(", ").ifBlank { unknownAuthor }
    /** "12,345": grouping by thousands, no locale needed for digits. */
    private fun n(x: Int) = x.toString().reversed().chunked(3).joinToString(",").reversed()
    fun pageLabel(p: app.epubreader.reader.PageIndicator): String? {
        val page = p.page ?: return null
        return when {
            p.total == null -> "Page ${n(page)}"
            p.chapterScope -> "Page ${n(page)} of ${n(p.total)} in chapter"
            p.exact -> "Page ${n(page)} of ${n(p.total)}"
            else -> "Page ${n(page)} of ≈ ${n(p.total)}"
        }
    }
    // --- M7 book details ---
    const val bookGone = "This book is no longer in the library."
    const val language = "Language"
    const val unknown = "Unknown"
    const val added = "Added"
    const val lastRead = "Last read"
    const val never = "Not yet"
    fun fileLocations(n: Int) = if (n == 1) "File" else "Files ($n copies, one entry)"
    const val identity = "Identity"
    /** "1.4 MB", "820 KB" (decimal units, one decimal for MB). */
    fun fileSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> "${(bytes / 100_000) / 10.0} MB"
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        else -> "$bytes bytes"
    }
    // --- M7 settings, about, backup ---
    const val sectionAppearance = "Appearance"
    const val appTheme = "App theme"
    const val themeSystem = "Follow system"
    const val themeLight = "Light"
    const val themeDark = "Dark"
    const val sectionLibrary = "Library"
    const val discovery = "Where to look for books"
    const val everywhere = "Everywhere on this device"
    const val oneFolder = "Only one folder…"
    fun onlyIn(folder: String) = "Only in “$folder” and its subfolders"
    const val folderView = "Folder view"
    const val folderViewSummary = "List the folders that hold books in the menu"
    const val hiddenBooks = "Hidden books"
    fun hiddenSummary(n: Int) = when (n) { 0 -> "None"; 1 -> "1 book removed from the library"; else -> "$n books removed from the library" }
    const val noHiddenBooks = "No hidden books. Books you remove from the library only (keeping their files) are listed here."
    const val showAgain = "Show again"
    const val sectionReading = "Reading"
    const val sectionBackup = "Backup"
    const val backUpNow = "Back up now"
    const val backUpSummary = "Save reading positions, bookmarks, highlights and notes, collections and settings to a file (books are not included)"
    const val restore = "Restore"
    const val restoreSummary = "Merge a backup file into this device's data"
    const val aboutApp = "About this app"
    const val aboutSummary = "Version, privacy, licences"
    fun version(v: String) = "Version $v"
    const val aboutBody = "An offline EPUB reader. FernReader finds the books on this device and opens them quickly. Free software under the MIT licence."
    const val privacyTitle = "Privacy"
    val privacyPoints = listOf(
        "Everything works without an account and without a network connection.",
        "FernReader does not connect to the internet. It has no network permission.",
        "No analytics, no tracking, no ads.",
        "Books are only read, never changed or uploaded. Backups are files you save yourself.",
    )
    const val licencesTitle = "Open-source licences"
    const val licencesBody = "This app is built with open-source libraries and fonts. Their licences are listed in full."
    const val showLicences = "Show licences"
    const val outsideTitle = "Books outside this folder"
    fun outsideBody(n: Int, folder: String) = "${books(n).replaceFirstChar { it.uppercase() }} in the library ${if (n == 1) "is" else "are"} outside “$folder”. Hide ${if (n == 1) "it" else "them"} from the library? Reading positions and collections are kept, and you can show hidden books again in Settings."
    const val hide = "Hide"
    const val keep = "Keep"
    const val folderNotOnDevice = "Choose a folder on this device's storage."
    const val restoreTitle = "Restore from backup?"
    fun restoreBody(date: String, books: Int, collections: Int) = "This backup from $date has ${books(books)} and ${if (collections == 1) "1 collection" else "$collections collections"}. It is merged with this device's data: for each item, the newer change is kept. Book files are not part of backups."
    const val restoring = "Restoring…"
    const val restoredTitle = "Backup restored"
    fun restoredBody(r: app.epubreader.core.data.RestoreResult) = buildString {
        append("${books(r.booksMatched).replaceFirstChar { it.uppercase() }} matched on this device.")
        if (r.booksWaiting > 0) append(" ${books(r.booksWaiting).replaceFirstChar { it.uppercase() }} ${if (r.booksWaiting == 1) "is" else "are"} not on this device; ${if (r.booksWaiting == 1) "its" else "their"} reading data is kept and comes back when the file is found.")
        append(" Updated: ${r.positions} reading positions, ${r.bookmarks} bookmarks, ${r.annotations} highlights and notes, ${r.collections} collections, ${r.settings} settings.")
    }
    const val ok = "OK"
    const val backupSavedTitle = "Backup saved"
    fun backupSavedBody(name: String) = "Saved as “$name”."
    const val backupProblemTitle = "Backup"
    fun backupProblem(p: app.epubreader.settings.BackupProblem) = when (p) {
        app.epubreader.settings.BackupProblem.NOT_A_BACKUP -> "This file isn't a backup from this app, or it is damaged."
        app.epubreader.settings.BackupProblem.TOO_NEW -> "This backup was made by a newer version of the app. Update the app, then restore it."
        app.epubreader.settings.BackupProblem.READ_FAILED -> "The file couldn't be read."
        app.epubreader.settings.BackupProblem.WRITE_FAILED -> "The backup couldn't be saved there. Try another place."
        app.epubreader.settings.BackupProblem.RESTORE_FAILED -> "The backup couldn't be restored. Nothing was changed."
    }
    const val textSize = "Text size"
    const val smallerText = "Smaller text"
    const val largerText = "Larger text"
    const val font = "Font"
    const val lineSpacing = "Line spacing"
    const val margins = "Side margins"
    const val alignment = "Alignment"
    const val alignPublisher = "As published"
    const val alignStart = "Left"
    const val alignJustify = "Justified"
    const val layout = "Reading mode"
    const val flowPaged = "Pages"
    const val flowScrolled = "Scroll"
    const val flowAuto = "Auto"
    const val pageNumbers = "Page numbers"
    const val scopeBook = "Whole book"
    const val scopeChapter = "This chapter"
    const val brightness = "Brightness"
    const val followSystem = "System"
    fun themeName(id: app.epubreader.core.data.ReadingThemeId) = when (id) {
        app.epubreader.core.data.ReadingThemeId.LIGHT -> "Light"
        app.epubreader.core.data.ReadingThemeId.DARK -> "Dark"
        app.epubreader.core.data.ReadingThemeId.BLACK -> "Black"
        app.epubreader.core.data.ReadingThemeId.WARM -> "Warm"
    }
    fun fontName(id: String) = when (id) {
        "literata" -> "Literata"
        "source-sans" -> "Source Sans"
        "atkinson" -> "Atkinson"
        else -> "Book's font"
    }
    const val search = "Search"
    const val addBookmark = "Add bookmark"
    const val removeBookmark = "Remove bookmark"
    const val bookmarkAdded = "Bookmark added"
    const val bookmarkRemoved = "Bookmark removed"
    /** Describes the ribbon drawn on a bookmarked page. */
    const val bookmarkedPage = "Bookmarked page"
    const val bookmarksTab = "Bookmarks"
    const val highlightsTab = "Highlights"
    const val noBookmarks = "No bookmarks yet. Tap the bookmark button in the reader's top bar to keep your place."
    const val noContents = "This book has no table of contents."
    const val noHighlights = "No highlights yet. Select text on a page to highlight it."
    /** The list's page estimate comes from the bookmark's place in the book, so it is approximate. */
    fun bookmarkPage(n: Int) = "Page ≈ $n"
    fun bookmarkPercent(p: Int) = "$p% through the book"
    // F-060 selection menu (the system's text actions, e.g. Translate, follow these)
    const val selHighlight = "Highlight"
    const val selUnderline = "Underline"
    const val selSearch = "Search in book"
    const val selCopy = "Copy"
    const val selShare = "Share"
    /** The colour row after Highlight/Underline (its accessibility label). */
    const val highlightColors = "Highlight colour"
    fun highlightColorName(id: String) = when (id) {
        "yellow" -> "Yellow"
        "green" -> "Green"
        "blue" -> "Blue"
        "pink" -> "Pink"
        "orange" -> "Orange"
        "purple" -> "Purple"
        else -> id
    }
    // F-061 highlight sheet, F-062 Highlights tab
    /** Accessibility label of the sheet opened by tapping a highlight. */
    const val highlightSheet = "Highlight"
    const val styleHighlight = "Highlight"
    const val styleUnderline = "Underline"
    const val note = "Note"
    const val notePlaceholder = "Add a note"
    const val done = "Done"
    const val removeHighlight = "Remove"
    /** The list row's remove button (accessibility label). */
    const val removeHighlightFromList = "Remove highlight"
    const val highlightRemoved = "Highlight removed"
    const val undo = "Undo"
    const val viewFullNote = "View full note"
    const val hideFullNote = "Show less"
    /** Describes the list's colour mark: e.g. "Yellow highlight", "Blue underline". */
    fun annotationMark(color: String, underline: Boolean) = "${highlightColorName(color)} ${if (underline) "underline" else "highlight"}"
    const val readerSettings = "Reader settings"
    const val settingsPageTurning = "Page turning"
    const val settingsScreen = "Screen"
    const val settingsLinks = "Links"
    const val pageAnimation = "Page-turn animation"
    const val animSlide = "Slide"
    const val animNone = "None"
    /** F-032 with F-031: page-turn animation has no effect while scrolling. */
    const val appliesToPageMode = "Applies to page mode"
    const val swipeDirection = "Swipe direction"
    const val swipeAuto = "Follow book"
    const val swipeLtr = "Left to right"
    const val swipeRtl = "Right to left"
    const val tapZones = "Tap screen edges to turn pages"
    const val volumeKeys = "Volume keys turn pages"
    const val volumeOff = "Off"
    const val volumeOn = "On"
    const val volumeReversed = "Reversed"
    fun volumeHint(v: app.epubreader.core.data.VolumeKeys) = when (v) {
        app.epubreader.core.data.VolumeKeys.OFF -> "The volume keys change the volume."
        app.epubreader.core.data.VolumeKeys.ON -> "Volume down: next page. Volume up: previous page."
        app.epubreader.core.data.VolumeKeys.REVERSED -> "Volume up: next page. Volume down: previous page."
    }
    /** C-005 on the settings screen: the choice and what it does. */
    fun volumeSummary(v: app.epubreader.core.data.VolumeKeys) = when (v) {
        app.epubreader.core.data.VolumeKeys.OFF -> volumeOff
        app.epubreader.core.data.VolumeKeys.ON -> volumeOn
        app.epubreader.core.data.VolumeKeys.REVERSED -> volumeReversed
    } + ". " + volumeHint(v)
    const val fullscreen = "Hide system bars"
    const val keepScreenOn = "Keep screen on while reading"
    const val orientation = "Orientation"
    const val orientSystem = "System"
    const val orientPortrait = "Portrait"
    const val orientLandscape = "Landscape"
    const val confirmLinks = "Ask before opening external links"
    /** F-044 confirmation: names the host (or the address for an email link). */
    fun openLinkTitle(label: String, mail: Boolean) = if (mail) "Write to $label?" else "Open $label in your browser?"
    fun openLinkBody(mail: Boolean) = if (mail) "This opens your email app." else "This link leads outside the book."
    const val openLink = "Open"
    const val noAppForLink = "No app on this device can open this link."
    const val searchInBook = "Search in this book"
    const val clearSearch = "Clear search text"
    const val wholeWords = "Whole words"
    const val searching = "Searching…"
    const val noResults = "No results"
    const val recentSearches = "Recent searches"
    const val clearHistory = "Clear history"
    const val noRecentSearches = "Type a word or phrase to look through the whole book. Your searches will be kept here."
    /** The count line: [capped] means the list stops at its limit; [running] with nothing found yet reads as waiting. */
    fun resultCount(total: Int, capped: Boolean, running: Boolean) = when {
        total == 0 -> if (running) searching else noResults
        capped -> "$total+ results"
        total == 1 -> "1 result"
        else -> "$total results"
    }
    const val contents = "Contents"
    const val close = "Close"
    const val untitledEntry = "Untitled"
    const val removeFromRecent = "Remove from library"
    const val hitNotFound = "Couldn't find this result on the page. It may have changed."
    const val missingTarget = "This entry points to a part that is missing from the book."
    const val missingPart = "This part of the book is missing from the file, so it can't be shown. You can keep reading."
    const val engineError = "This book can't be displayed."
    /** C-048: the detail line under [engineError]; package problems are worded, anything else keeps the engine's message. */
    fun engineDetail(e: ReaderError.Engine) = when (e.kind) {
        "no_package" -> "No package found in this file."
        "nothing_readable" -> "This book has no readable sections."
        else -> e.message
    }
    const val fileChanged = "This book's file was changed or removed, so it was closed."
    const val missingTitle = "File not found"
    fun missingBody(title: String) = "The file for “$title” is no longer where it was. It may have been moved or deleted."
    const val removeEntry = "Remove from library"
    const val cancel = "Cancel"
    const val webViewTooOld = "This device's web engine is too old to display books. Update \"Android System WebView\" in the app store, then reopen this app."
    fun openFailure(r: OpenFailure) = when (r) {
        OpenFailure.NO_ACCESS -> "This book can't be opened. The file may have been moved or deleted, or access to it was removed."
        OpenFailure.NOT_A_ZIP -> "This file isn't an EPUB book."
        OpenFailure.DAMAGED -> "This book file is damaged and can't be opened."
    }
}
