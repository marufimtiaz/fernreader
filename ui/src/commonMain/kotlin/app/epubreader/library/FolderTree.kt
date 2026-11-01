package app.epubreader.library

/**
 * One row of the folder tree (F-017). [direct] books are in this folder itself, [total] also counts subfolders.
 * [parent] is the parent's path below its storage volume ("" at the top), so same-named folders can be told apart.
 */
data class FolderNode(
    val path: String, val name: String, val parent: String, val depth: Int,
    val direct: Int, val total: Int, val hasChildren: Boolean, val expanded: Boolean,
)

/**
 * Builds the tree of folders that hold books, mirroring the directory structure (F-017, R-010). On the device's own
 * storage the top-level folders are the roots (the storage root is a row only when books sit directly in it); another
 * volume (SD card, USB) is a row of its own with its folders below.
 */
object FolderTree {
    private val PRIMARY = Regex("^/storage/emulated/\\d+")
    private val OTHER = Regex("^/storage/[^/]+")

    fun volumeOf(path: String): String = PRIMARY.find(path)?.value ?: OTHER.find(path)?.value ?: ""
    private fun isPrimary(path: String) = PRIMARY.containsMatchIn(path)
    private fun relative(path: String): String = path.removePrefix(volumeOf(path)).trim('/')

    private fun parentOf(path: String): String? {
        val vol = volumeOf(path)
        val rel = relative(path)
        return when {
            rel.isEmpty() -> null // a volume row
            rel.contains('/') -> vol + "/" + rel.substringBeforeLast('/')
            vol.isNotEmpty() && !isPrimary(path) -> vol
            else -> null
        }
    }

    /** [direct]: folder path → number of books directly inside. Rows in depth-first order, names sorted ignoring case. */
    fun build(direct: Map<String, Int>, collapsed: Set<String>): List<FolderNode> = build(direct) { it !in collapsed }

    /** As above; a folder's subfolders are rows only where [isExpanded] holds for it. */
    fun build(direct: Map<String, Int>, isExpanded: (String) -> Boolean): List<FolderNode> {
        val counts = HashMap<String, Int>()
        for ((f, n) in direct) { val k = f.trimEnd('/').ifEmpty { "/" }; counts[k] = (counts[k] ?: 0) + n }
        val nodes = HashSet<String>()
        for (f in counts.keys) {
            var p: String? = f
            while (p != null && nodes.add(p)) p = parentOf(p)
        }
        val children = nodes.groupBy { parentOf(it) }
        val totals = HashMap<String, Int>()
        fun total(p: String): Int = totals.getOrPut(p) { (counts[p] ?: 0) + children[p].orEmpty().sumOf { total(it) } }
        val byName = compareBy<String> { relative(it).isNotEmpty() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.substringAfterLast('/') }.thenBy { it }
        val out = ArrayList<FolderNode>()
        fun visit(p: String, depth: Int) {
            val kids = children[p].orEmpty().sortedWith(byName)
            val rel = relative(p)
            val expanded = isExpanded(p)
            out += FolderNode(
                path = p, name = if (rel.isEmpty()) p else rel.substringAfterLast('/'), parent = rel.substringBeforeLast('/', ""),
                depth = depth, direct = counts[p] ?: 0, total = total(p), hasChildren = kids.isNotEmpty(), expanded = expanded,
            )
            if (expanded) kids.forEach { visit(it, depth + 1) }
        }
        children[null].orEmpty().sortedWith(compareBy<String> { !isPrimary(it) }.then(byName)).forEach { visit(it, 0) }
        return out
    }
}
