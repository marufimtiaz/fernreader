package app.epubreader.library

import app.epubreader.ui.Copy

/** A folder row in the navigation drawer (C-074). [parentLine] is shown under the name when another folder has the same name. */
data class DrawerFolder(val node: FolderNode, val parentLine: String?)

/**
 * The drawer's "Folders" section (C-074, F-017): the folders that hold books as a tree, collapsed below the top level
 * except for the folders in [build]'s `expanded`. Long lists stop after [CAP] rows and end with [showAll] (the
 * full-screen tree).
 */
data class DrawerFolders(val rows: List<DrawerFolder> = emptyList(), val showAll: Boolean = false) {
    companion object {
        const val CAP = 15

        fun build(direct: Map<String, Set<String>>, expanded: Set<String>, cap: Int = CAP): DrawerFolders {
            if (direct.isEmpty()) return DrawerFolders()
            // names are compared over the whole tree, so a folder is told apart even while its namesake is folded away
            val names = FolderTree.build(direct, collapsed = emptySet()).groupingBy { it.name.lowercase() }.eachCount()
            val visible = FolderTree.build(direct) { it in expanded }
            val rows = visible.take(cap).map { n -> DrawerFolder(n, if ((names[n.name.lowercase()] ?: 0) > 1) parentLine(n) else null) }
            return DrawerFolders(rows, showAll = visible.size > cap)
        }

        /** The parent's path below its volume, or the volume itself for a top-level folder ("Device storage"). */
        private fun parentLine(n: FolderNode): String =
            n.parent.ifEmpty { Copy.folderName(FolderTree.volumeOf(n.path)).ifEmpty { "/" } }
    }
}
