package app.epubreader.core.epub.cfi

/** One `/N[assertion]` step; [indirect] marks a trailing `!` (step into the referenced document). */
data class CfiStep(val index: Int, val assertion: String? = null, val indirect: Boolean = false)

/** Steps plus the raw terminal after the last one (`:12[...]`, `~1.5`, `@1:2`, `[;s=a]` ...), kept verbatim. */
data class CfiPath(val steps: List<CfiStep>, val terminal: String = "")

/**
 * Minimal EPUB CFI model: enough to rewrite step indices (the chunk map) while printing everything else exactly as
 * it was read, so CFIs written by the reader (foliate-js) survive a round trip unchanged. A range is
 * `parent,start,end` with [start] and [end] relative to [parent].
 */
data class Cfi(val parent: CfiPath, val start: CfiPath? = null, val end: CfiPath? = null) {
    val isRange: Boolean get() = start != null

    fun startPoint(): CfiPath = if (start == null) parent else CfiPath(parent.steps + start.steps, start.terminal)
    fun endPoint(): CfiPath = if (end == null) parent else CfiPath(parent.steps + end.steps, end.terminal)

    override fun toString(): String = buildString {
        append("epubcfi(")
        appendPath(parent)
        if (start != null && end != null) { append(','); appendPath(start); append(','); appendPath(end) }
        append(')')
    }

    private fun StringBuilder.appendPath(p: CfiPath) {
        for (st in p.steps) {
            append('/').append(st.index)
            st.assertion?.let { append('[').append(it).append(']') }
            if (st.indirect) append('!')
        }
        append(p.terminal)
    }

    companion object {
        /** Null for anything that is not a well-formed `epubcfi(...)`; never throws. */
        fun parse(s: String): Cfi? {
            val t = s.trim()
            if (!t.startsWith("epubcfi(") || !t.endsWith(")")) return null
            val parts = splitTopLevel(t.substring(8, t.length - 1)) ?: return null
            return when (parts.size) {
                1 -> parsePath(parts[0], needSteps = true)?.let { Cfi(it) }
                3 -> {
                    val p = parsePath(parts[0], needSteps = true) ?: return null
                    val a = parsePath(parts[1], needSteps = false) ?: return null
                    val b = parsePath(parts[2], needSteps = false) ?: return null
                    Cfi(p, a, b)
                }
                else -> null
            }
        }

        /** A range between two full points, factoring out their common step prefix (at least one step stays in each). */
        fun range(a: CfiPath, b: CfiPath): Cfi {
            var n = 0
            while (n < a.steps.size - 1 && n < b.steps.size - 1 && a.steps[n] == b.steps[n]) n++
            return Cfi(CfiPath(a.steps.take(n)), CfiPath(a.steps.drop(n), a.terminal), CfiPath(b.steps.drop(n), b.terminal))
        }

        /** Splits at commas outside brackets; `^` escapes the next character. Null on unbalanced brackets. */
        private fun splitTopLevel(s: String): List<String>? {
            val out = ArrayList<String>(3)
            var depth = 0
            var last = 0
            var i = 0
            while (i < s.length) {
                when (s[i]) {
                    '^' -> i++
                    '[' -> depth++
                    ']' -> if (--depth < 0) return null
                    ',' -> if (depth == 0) { out += s.substring(last, i); last = i + 1 }
                }
                i++
            }
            if (depth != 0) return null
            out += s.substring(last)
            return out
        }

        private fun parsePath(s: String, needSteps: Boolean): CfiPath? {
            val steps = ArrayList<CfiStep>()
            var i = 0
            while (i < s.length && s[i] == '/') {
                i++
                val ns = i
                while (i < s.length && s[i].isDigit()) i++
                if (i == ns || i - ns > 9) return null
                val idx = s.substring(ns, i).toInt()
                var assertion: String? = null
                if (i < s.length && s[i] == '[') {
                    val a = ++i
                    while (i < s.length && s[i] != ']') { if (s[i] == '^') i++; i++ }
                    if (i >= s.length) return null
                    assertion = s.substring(a, i)
                    i++
                }
                var indirect = false
                if (i < s.length && s[i] == '!') { indirect = true; i++ }
                steps += CfiStep(idx, assertion, indirect)
            }
            if (needSteps && steps.isEmpty()) return null
            val terminal = s.substring(i)
            if (terminal.isNotEmpty() && terminal[0] !in ":~@[") return null
            return CfiPath(steps, terminal)
        }
    }
}
