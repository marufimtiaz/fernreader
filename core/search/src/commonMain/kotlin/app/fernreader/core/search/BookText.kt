package app.fernreader.core.search

import app.fernreader.core.epub.text.ExtractedText

/** The book's text as the index and the reader's text resolver see it: one entry per spine item, in spine order. */
interface BookText {
    val sectionCount: Int

    /** Canonical text and segments of spine item [index]; null if it is missing or unreadable (it then has no hits). */
    fun section(index: Int): ExtractedText?
}

/** The book went away under a reader of it (closed session, removed file): stop now, try again later. */
class BookUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
