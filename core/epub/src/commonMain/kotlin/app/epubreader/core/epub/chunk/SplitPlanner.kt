package app.epubreader.core.epub.chunk

import app.epubreader.core.epub.markup.Attr
import app.epubreader.core.epub.markup.HTML_VOID
import app.epubreader.core.epub.markup.MarkupScanner
import app.epubreader.core.epub.markup.MarkupVisitor

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
 * No plan (null) unless the markup is balanced the way an XML parser sees it: every element closed by an end tag
 * with the same name (HTML void elements may stay unclosed), no scanner errors. Then the element children the
 * reader's DOM sees are the ones counted here, which the CFI mapping depends on.
 */
internal object SplitPlanner {
    fun plan(s: String, targetChars: Int): SplitPlan? {
        val v = Visitor()
        MarkupScanner.scan(s, v)
        if (v.bad || v.bodyEnd < 0 || v.stack.size != 1) return null
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

    private class Visitor : MarkupVisitor {
        val stack = ArrayList<String>()
        var bad = false
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

        private val bodyDepth get() = if (bodyContentStart < 0 || bodyEnd >= 0) -1 else 2

        override fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) {
            if (bad || bodyEnd >= 0) return
            val void = selfClosing || name.lowercase() in HTML_VOID
            val depth = stack.size   // depth of the new element's parent
            if (depth == 1) {
                rootChildren++
                if (bodyContentStart < 0 && name.substringAfter(':').lowercase() == "body" && !void) {
                    bodyStep = 2 * rootChildren
                    bodyContentStart = end
                }
            }
            val bd = bodyDepth
            if (bd > 0 && depth == bd) {
                if (bodyChildren.size == 0 && !void) { wrapperName = name; wrapperOpenEnd = end }
                if (void) { bodyChildren.add(start); bodyChildren.add(end) }
            }
            if (bd > 0 && depth == bd + 1 && bodyChildren.size == 0 && void) { firstChildChildren.add(start); firstChildChildren.add(end) }
            if (!void) { stack += name; openStarts.add(start) }
        }

        override fun endTag(name: String, start: Int, end: Int) {
            if (bad || bodyEnd >= 0) return
            if (stack.isEmpty() || stack.last() != name) {
                // An unclosed void element closed explicitly (<br></br>) is fine; anything else is unbalanced.
                if (name.lowercase() !in HTML_VOID) bad = true
                return
            }
            stack.removeAt(stack.size - 1)
            val elemStart = openStarts.removeLast()
            val depth = stack.size   // depth of the closed element's parent
            val bd = bodyDepth
            if (bd > 0 && depth == bd - 1) { bodyEnd = start; return }   // </body>
            if (bd > 0 && depth == bd) {
                if (bodyChildren.size == 0) wrapperCloseStart = start
                bodyChildren.add(elemStart); bodyChildren.add(end)
            }
            if (bd > 0 && depth == bd + 1 && bodyChildren.size == 0) { firstChildChildren.add(elemStart); firstChildChildren.add(end) }
        }

        override fun error(message: String, at: Int) { if (bodyEnd < 0) bad = true }
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
