package app.epubreader.core.data

/**
 * Document order of EPUB CFIs, compared by their step paths (no document needed). Steps are integers, optionally
 * followed by `[id]` and `:offset`; `!` joins the package path to the document path. A range `epubcfi(P,S,E)` sorts at
 * its start, P+S. A CFI that does not parse sorts after every valid one (and equal to other malformed ones).
 */
object CfiOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        if (pa == null || pb == null) return if (pa == null && pb == null) 0 else if (pa == null) 1 else -1
        val n = minOf(pa.size, pb.size)
        for (i in 0 until n) {
            val c = pa[i].compareTo(pb[i])
            if (c != 0) return c
        }
        return pa.size.compareTo(pb.size)
    }

    /** The flat list of numbers to compare: each step, `!` as -1 (never equal to a step), a character offset after its step. */
    private fun parse(cfi: String): List<Long>? {
        var s = cfi.trim()
        if (s.startsWith("epubcfi(") && s.endsWith(")")) s = s.substring(8, s.length - 1)
        if (s.isEmpty()) return null
        // Range: P,S,E -> P+S. Commas inside [ids] are not expected (ids are escaped with ^ in CFIs).
        val parts = s.split(',')
        val start = when (parts.size) { 1 -> parts[0]; 3 -> parts[0] + parts[1]; else -> return null }
        val out = ArrayList<Long>()
        for (seg in start.split('!')) {
            if (seg.isEmpty() || seg[0] != '/') return null
            for (step in seg.substring(1).split('/')) {
                val noId = step.replace(ID, "")
                val colon = noId.indexOf(':')
                val num = (if (colon >= 0) noId.substring(0, colon) else noId).toLongOrNull() ?: return null
                out += num
                if (colon >= 0) {
                    // "10:5" offset may carry a "~temporal" or "@spatial" tail; only the integer part orders text.
                    val off = noId.substring(colon + 1).takeWhile { it.isDigit() }.toLongOrNull() ?: return null
                    out += off
                }
            }
            out += SEP
        }
        out.removeAt(out.lastIndex)
        return out
    }

    private val ID = Regex("\\[[^\\]]*\\]")
    private const val SEP = -1L
}
