package app.fernreader.core.data

/**
 * C-096: byte-identical files that the user keeps as separate library entries. File names never decide which entry a
 * file belongs to: a known file stays with its entry, and for a new file only what went missing counts.
 */
object SeparateCopies {
    /** A key no file can yield, so [LibraryDao.byIdentity] of a file's content never finds a separate copy. */
    fun identityKey(contentKey: String): String = "$contentKey+copy-${newId()}"

    /** The order book details lists a book's files in; the first one can't become a separate copy. */
    val fileOrder: Comparator<BookFileEntity> = compareBy({ it.source != "scan" }, { it.path })

    data class Found(val path: String, val mtimeMs: Long)

    /** An entry short of a file: [path] and [mtimeMs] are what it last had. */
    data class Claim(val bookId: String, val separate: Boolean, val path: String?, val mtimeMs: Long?)

    /**
     * Which of the [claims] takes which of the [found] files, by path; a file not in the result joins the book of its
     * content (F-015), as a brand-new identical file does. The evidence, strongest first:
     * 1. the same path: a copy with no file finds it where it last was;
     * 2. the same modified time: a rename or move keeps it, so a move of the other entry's file isn't mistaken for this one;
     * 3. a separate copy short of a file takes any file left, since the move may have changed the time (copy and delete).
     *
     * Equal evidence (two entries' files moved in one scan with the same modified time) has no right answer: both
     * lists are sorted so the result is stable, and each entry still ends up with one file of the same content.
     */
    fun assign(found: List<Found>, claims: List<Claim>): Map<String, String> {
        val files = found.sortedBy { it.path }.toMutableList()
        val open = claims.sortedBy { it.path }.toMutableList()
        val owners = HashMap<String, String>()
        fun pass(pick: (Claim) -> Found?) {
            for (c in open.toList()) pick(c)?.let { f -> owners[f.path] = c.bookId; files -= f; open -= c }
        }
        pass { c -> files.firstOrNull { it.path == c.path } }
        pass { c -> files.firstOrNull { it.mtimeMs == c.mtimeMs } }
        pass { c -> if (c.separate) files.firstOrNull() else null }
        return owners
    }
}
