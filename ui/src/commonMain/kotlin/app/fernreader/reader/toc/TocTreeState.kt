package app.fernreader.reader.toc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.fernreader.reader.bridge.TocItem

/** Expand/collapse over a depth-annotated flat TOC. O(n) to build; rows are recomputed only on toggle. */
class TocTreeState(val items: List<TocItem>) {
    private val parent = IntArray(items.size)
    private val children = BooleanArray(items.size)
    private val byHref = HashMap<String, Int>(items.size * 2)
    private var expanded by mutableStateOf(emptySet<Int>())

    init {
        val stack = ArrayList<Int>()
        items.forEachIndexed { i, it ->
            while (stack.isNotEmpty() && items[stack.last()].depth >= it.depth) stack.removeAt(stack.lastIndex)
            parent[i] = stack.lastOrNull() ?: -1
            if (parent[i] >= 0) children[parent[i]] = true
            stack += i
            byHref.getOrPut(it.href) { i }
        }
    }

    fun hasChildren(i: Int) = children[i]
    fun isExpanded(i: Int) = i in expanded
    fun toggle(i: Int) { expanded = if (i in expanded) expanded - i else expanded + i }
    fun indexOfHref(href: String?): Int? = href?.let { byHref[it] }

    fun reveal(i: Int) {
        var p = parent[i]; val add = HashSet<Int>()
        while (p >= 0) { add += p; p = parent[p] }
        if (add.isNotEmpty()) expanded = expanded + add
    }

    fun visibleRows(): List<Int> {
        val out = ArrayList<Int>()
        val open = expanded
        var hideDeeperThan = Int.MAX_VALUE
        for (i in items.indices) {
            val d = items[i].depth
            if (d > hideDeeperThan) continue
            hideDeeperThan = Int.MAX_VALUE
            out += i
            if (children[i] && i !in open) hideDeeperThan = d
        }
        return out
    }
}
