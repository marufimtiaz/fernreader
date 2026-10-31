package app.epubreader.core.search

/** FTS5 `highlight(seg, 0, char(1), char(2))` output split into the plain row text and the marked spans. */
internal class Highlighted(val plain: String, val starts: IntArray, val ends: IntArray)

internal object Highlights {
    private const val OPEN = '\u0001'
    private const val CLOSE = '\u0002'

    /** Index rows never contain the markers (the builder blanks them), so every marker pair is FTS5's. */
    fun parse(marked: String): Highlighted {
        val sb = StringBuilder(marked.length)
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        for (c in marked) when (c) {
            OPEN -> starts += sb.length
            CLOSE -> if (ends.size < starts.size) ends += sb.length
            else -> sb.append(c)
        }
        val n = minOf(starts.size, ends.size)
        return Highlighted(sb.toString(), starts.toIntArray().copyOf(n), ends.toIntArray().copyOf(n))
    }
}
