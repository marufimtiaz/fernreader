package app.epubreader.ui

import app.epubreader.reader.OpenFailure

/** All user-facing text (our own wording). Moves to Compose resources when the app is translated. */
object Copy {
    const val appTitle = "Books"
    const val openBook = "Open book"
    const val recentEmpty = "No books yet. Use “Open book” to choose an EPUB file."
    const val back = "Back"
    const val contents = "Contents"
    const val close = "Close"
    const val untitledEntry = "Untitled"
    const val removeFromRecent = "Remove from list"
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
