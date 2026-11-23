package app.fernreader.core.epub

/** A position in a spine item's canonical text (`TextExtractor` output): UTF-16 [offset] into spine item [spineIndex]. */
data class TextPos(val spineIndex: Int, val offset: Int)
