package app.fernreader.core.epub

/**
 * Maps positions between what the reader shows (virtual documents) and what is stored (original documents).
 * Identity until M8, where ChunkMap implements it for chunked books. Every CFI that crosses between the reader
 * and the database goes through this, so stored data never contains chunk-part positions.
 */
interface PositionMap {
    fun cfiToOriginal(virtualCfi: String): String
    fun cfiToVirtual(originalCfi: String): String
    /** Text positions (search hits) between the original spine and the reader's virtual one. */
    fun textToVirtual(p: TextPos): TextPos
    fun textToOriginal(p: TextPos): TextPos

    companion object {
        val Identity: PositionMap = object : PositionMap {
            override fun cfiToOriginal(virtualCfi: String) = virtualCfi
            override fun cfiToVirtual(originalCfi: String) = originalCfi
            override fun textToVirtual(p: TextPos) = p
            override fun textToOriginal(p: TextPos) = p
        }
    }
}
