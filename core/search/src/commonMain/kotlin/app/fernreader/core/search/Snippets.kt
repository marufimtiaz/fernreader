package app.fernreader.core.search

internal class Snippet(val text: String, val hitStart: Int, val hitEnd: Int)

internal object Snippets {
    const val WIDTH = 200

    /**
     * About [WIDTH] characters of [text] around the hit `hitStart until hitEnd`, cut at word boundaries, whitespace runs
     * collapsed to one space, "…" where text was cut. The returned hit range indexes into the snippet.
     */
    fun build(text: String, hitStart: Int, hitEnd: Int, width: Int = WIDTH): Snippet {
        val context = maxOf(0, (width - (hitEnd - hitStart)) / 2)
        var from = maxOf(0, hitStart - context)
        var to = minOf(text.length, hitEnd + context)
        // Move the cuts to word boundaries without losing the hit.
        if (from > 0 && !text[from - 1].isWhitespace() && !text[from].isWhitespace()) {
            var i = from
            while (i < hitStart && !text[i].isWhitespace()) i++
            from = i
        }
        if (to < text.length && !text[to].isWhitespace() && !text[to - 1].isWhitespace()) {
            var i = to
            while (i > hitEnd && !text[i - 1].isWhitespace()) i--
            to = i
        }
        val body = StringBuilder(to - from)
        var s = -1
        var e = -1
        var space = false
        for (i in from until to) {
            val c = text[i]
            if (c.isWhitespace()) { space = body.isNotEmpty(); continue }
            if (space) { body.append(' '); space = false }
            if (i == hitStart) s = body.length
            body.append(c)
            if (i == hitEnd - 1) e = body.length
        }
        val lead = if (from > 0) "\u2026" else ""
        val out = lead + body + if (to < text.length) "\u2026" else ""
        return Snippet(out, lead.length + maxOf(0, s), lead.length + (if (e < 0) body.length else e))
    }
}
