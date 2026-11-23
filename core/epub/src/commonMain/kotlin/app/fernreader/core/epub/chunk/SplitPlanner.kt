package app.fernreader.core.epub.chunk

import app.fernreader.core.epub.markup.Attr
import app.fernreader.core.epub.markup.MarkupScanner
import app.fernreader.core.epub.markup.MarkupVisitor

/** Where to cut one document; all offsets are into its decoded markup. */
internal class SplitPlan(
    /** CFI step of `<body>` under the root element. */
    val bodyStep: Int,
    /** End of the `<body ...>` start tag. */
    val bodyContentStart: Int,
    /** Start of `</body>`. */
    val bodyEnd: Int,
    val wrapper: Wrapper?,
    val parts: List<PlanPart>,
) {
    /** The body's single element child; its start tag is repeated in every part. */
    class Wrapper(val name: String, val openStart: Int, val openEnd: Int, val closeStart: Int, val closeEnd: Int)

    /** Container markup `[sliceStart, sliceEnd)` holding element children `[childOffset, childOffset + childCount)`. */
    class PlanPart(val sliceStart: Int, val sliceEnd: Int, val childOffset: Int, val childCount: Int)
}

/**
 * Plans the split of one huge document in a single forward scan (no DOM; R-006). The container is the body or,
 * when the body has exactly one element child that itself has several, that wrapper. Parts are contiguous slices
 * of the container cut only right after a complete child element, each at least [plan]'s `targetChars` except the
 * last (a last part under half the target joins the one before).
 *
 * No plan (null) unless an XML parser would accept the document (see [Visitor]): only then does the reader build
 * exactly the element children counted here, which the CFI mapping depends on. Anything else is served unsplit.
 */
internal object SplitPlanner {
    fun plan(s: String, targetChars: Int): SplitPlan? {
        val v = Visitor(s)
        MarkupScanner.scan(s, v)
        v.finish()
        if (v.bad || v.bodyEnd < 0) return null
        val wrapped = v.bodyChildren.size == 2 && v.firstChildChildren.size >= 4
        val children = if (wrapped) v.firstChildChildren else v.bodyChildren
        val n = children.size / 2
        if (n < 2) return null
        val innerStart = if (wrapped) v.wrapperOpenEnd else v.bodyContentStart
        val innerEnd = if (wrapped) v.wrapperCloseStart else v.bodyEnd

        val cuts = ArrayList<Int>()   // index of the last child of each part except the final one
        var partStart = innerStart
        for (k in 0 until n - 1) {
            val end = children[2 * k + 1]
            if (end - partStart >= targetChars) { cuts += k; partStart = end }
        }
        if (cuts.isNotEmpty() && innerEnd - partStart < targetChars / 2) cuts.removeAt(cuts.size - 1)
        if (cuts.isEmpty()) return null

        val parts = ArrayList<SplitPlan.PlanPart>(cuts.size + 1)
        var start = innerStart
        var first = 0
        for (k in cuts) {
            val end = children[2 * k + 1]
            parts += SplitPlan.PlanPart(start, end, first, k - first + 1)
            start = end
            first = k + 1
        }
        parts += SplitPlan.PlanPart(start, innerEnd, first, n - first)
        val wrapper = if (!wrapped) null else
            SplitPlan.Wrapper(v.wrapperName, v.bodyChildren[0], v.wrapperOpenEnd, v.wrapperCloseStart, v.bodyChildren[1])
        return SplitPlan(v.bodyStep, v.bodyContentStart, v.bodyEnd, wrapper, parts)
    }

    private const val XHTML_NS = "http://www.w3.org/1999/xhtml"
    private val XML_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")

    /**
     * Collects the structure and checks that an XML parser would accept the document as XHTML (the reader
     * re-parses it as HTML otherwise, which can build other children): one root `html` in the XHTML namespace,
     * every element closed with the same name (no HTML void-element leniency), no scanner errors, only XML or
     * numeric character references (HTML named ones only under an XHTML public doctype), no `<` outside CDATA in
     * script/style text, and no undeclared namespace prefixes.
     */
    private class Visitor(private val s: String) : MarkupVisitor {
        val stack = ArrayList<String>()
        var bad = false
        var rootDone = false
        var rootChildren = 0
        var bodyStep = -1
        var bodyContentStart = -1
        var bodyEnd = -1
        /** [start, end) pairs of the body's element children, and of its first element child's children. */
        val bodyChildren = IntList()
        val firstChildChildren = IntList()
        var wrapperName = ""
        var wrapperOpenEnd = -1
        var wrapperCloseStart = -1
        private val openStarts = IntList()   // start offset per open element (parallel to stack)
        private var htmlEntities = false
        private val usedPrefixes = HashSet<String>()
        private val declaredPrefixes = hashSetOf("xml")

        private val inBody get() = bodyContentStart >= 0 && bodyEnd < 0

        override fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) {
            if (bad) return
            if (rootDone) { bad = true; return }
            if (stack.isEmpty()) {
                // The root: XHTML namespace, and the prolog decides whether HTML named entities are defined.
                if (name != "html" || attrs.none { it.name == "xmlns" && it.value == XHTML_NS } || selfClosing) { bad = true; return }
                htmlEntities = s.substring(0, start).contains("-//W3C//DTD XHTML")
            }
            prefixes(name, attrs, start, end)
            val depth = stack.size   // depth of the new element's parent
            if (depth == 1) {
                rootChildren++
                if (bodyContentStart < 0 && name == "body" && !selfClosing) {
                    bodyStep = 2 * rootChildren
                    bodyContentStart = end
                }
            }
            if (inBody && depth == 2) {
                if (bodyChildren.size == 0 && !selfClosing) { wrapperName = name; wrapperOpenEnd = end }
                if (selfClosing) { bodyChildren.add(start); bodyChildren.add(end) }
            }
            if (inBody && depth == 3 && bodyChildren.size == 0 && selfClosing) { firstChildChildren.add(start); firstChildChildren.add(end) }
            if (!selfClosing) { stack += name; openStarts.add(start) }
        }

        override fun endTag(name: String, start: Int, end: Int) {
            if (bad) return
            if (stack.isEmpty() || stack.last() != name) { bad = true; return }
            stack.removeAt(stack.size - 1)
            val elemStart = openStarts.removeLast()
            val depth = stack.size   // depth of the closed element's parent
            if (stack.isEmpty()) { rootDone = true; return }
            if (inBody && depth == 1) { bodyEnd = start; return }   // </body>
            if (inBody && depth == 2) {
                if (bodyChildren.size == 0) wrapperCloseStart = start
                bodyChildren.add(elemStart); bodyChildren.add(end)
            }
            if (inBody && depth == 3 && bodyChildren.size == 0) { firstChildChildren.add(elemStart); firstChildChildren.add(end) }
        }

        override fun text(start: Int, end: Int) {
            if (bad) return
            if (stack.isEmpty()) { if (!isBlank(start, end)) bad = true; return }
            // Script/style text comes through raw: only CDATA sections may hold '<' or '&'.
            var i = start
            while (i < end) {
                val c = s[i]
                if (c == '<') {
                    if (!s.startsWith("<![CDATA[", i)) { bad = true; return }
                    val e = s.indexOf("]]>", i + 9)
                    if (e < 0 || e + 3 > end) { bad = true; return }
                    i = e + 3
                    continue
                }
                if (c == '&') { i = reference(i, end); if (i < 0) { bad = true; return }; continue }
                i++
            }
        }

        override fun error(message: String, at: Int) { bad = true }

        fun finish() {
            if (!rootDone || usedPrefixes.any { it !in declaredPrefixes }) bad = true
        }

        private fun prefixes(name: String, attrs: List<Attr>, start: Int, end: Int) {
            if (':' in name) usedPrefixes += name.substringBefore(':')
            for (a in attrs) {
                when {
                    a.name.startsWith("xmlns:") -> declaredPrefixes += a.name.substring(6)
                    a.name == "xmlns" -> {}
                    ':' in a.name -> usedPrefixes += a.name.substringBefore(':')
                }
                if (a.valueStart >= 0) {
                    var i = a.valueStart
                    while (i < a.valueEnd) {
                        when (s[i]) {
                            '&' -> { i = reference(i, a.valueEnd); if (i < 0) { bad = true; return } }
                            '<' -> { bad = true; return }
                            else -> i++
                        }
                    }
                }
            }
        }

        /** Validates the character reference at [i]; returns the index after it, or -1. */
        private fun reference(i: Int, end: Int): Int {
            val semi = s.indexOf(';', i)
            if (semi < 0 || semi >= end || semi - i > 40) return -1
            val ref = s.substring(i + 1, semi)
            val ok = when {
                ref.startsWith("#x") || ref.startsWith("#X") -> ref.length > 2 && ref.substring(2).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                ref.startsWith("#") -> ref.length > 1 && ref.substring(1).all { it.isDigit() }
                ref in XML_ENTITIES -> true
                else -> htmlEntities && ref.isNotEmpty() && ref.all { it.isLetterOrDigit() }
            }
            return if (ok) semi + 1 else -1
        }

        private fun isBlank(start: Int, end: Int): Boolean { for (i in start until end) if (!s[i].isWhitespace()) return false; return true }
    }
}

/** Growable int array (offset pairs for documents with hundreds of thousands of elements). */
internal class IntList {
    private var a = IntArray(64)
    var size = 0
        private set
    fun add(x: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = x }
    fun removeLast(): Int = a[--size]
    operator fun get(i: Int) = a[i]
}
