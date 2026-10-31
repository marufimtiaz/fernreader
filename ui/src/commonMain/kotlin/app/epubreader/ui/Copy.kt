package app.epubreader.ui

import app.epubreader.reader.OpenFailure

/** All user-facing text (our own wording). Moves to Compose resources when the app is translated. */
object Copy {
    const val appTitle = "Books"
    const val appName = "EPUB Reader"
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
    const val includeSubfolders = "Subfolders"
    const val expand = "Show subfolders"
    const val collapse = "Hide subfolders"
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
