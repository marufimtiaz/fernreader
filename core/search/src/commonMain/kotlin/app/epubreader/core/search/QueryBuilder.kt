package app.epubreader.core.search

/**
 * Turns the user's text into an FTS5 MATCH string. The text is split with the index tokenizer's rule (unicode61: letters,
 * digits and combining marks are token characters, everything else separates), so "prince's" and "well-known" become
 * the phrases `"prince s"` and `"well known"`. Only token characters ever reach MATCH, inside one quoted phrase.
 */
object QueryBuilder {
    fun build(query: SearchQuery): String? {
        val tokens = tokens(query.text)
        if (tokens.isEmpty()) return null
        return "\"" + tokens.joinToString(" ") + "\"" + if (query.wholeWord) "" else "*"
    }

    internal fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (c in text) {
            if (isTokenChar(c)) cur.append(c) else if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun isTokenChar(c: Char): Boolean = c.isLetterOrDigit() || c.isSurrogate() || when (c.category) {
        CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK -> true
        else -> false
    }
}
