package app.epubreader.ui

import app.epubreader.reader.OpenFailure

/** All user-facing text (our own wording). Moves to Compose resources when the app is translated. */
object Copy {
    const val appTitle = "Books"
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
    const val contents = "Contents"
    const val close = "Close"
    const val untitledEntry = "Untitled"
    const val removeFromRecent = "Remove from library"
    const val missingTarget = "This entry points to a part that is missing from the book."
    const val missingPart = "This part of the book is missing from the file, so it can't be shown. You can keep reading."
    const val engineError = "This book can't be displayed."
    const val webViewTooOld = "This device's web engine is too old to display books. Update \"Android System WebView\" in the app store, then reopen this app."
    fun openFailure(r: OpenFailure) = when (r) {
        OpenFailure.NO_ACCESS -> "This book can't be opened. The file may have been moved or deleted, or access to it was removed."
        OpenFailure.NOT_A_ZIP -> "This file isn't an EPUB book."
        OpenFailure.DAMAGED -> "This book file is damaged and can't be opened."
    }
}
