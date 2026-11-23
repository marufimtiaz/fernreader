package app.fernreader.settings

/** A piece of docs/licenses.md as the about screen shows it. */
sealed interface LicenceBlock {
    data class Heading(val text: String, val level: Int) : LicenceBlock
    data class Paragraph(val text: String) : LicenceBlock
    /** One table row with its table's header cells. */
    data class Row(val cells: List<String>, val header: List<String>) : LicenceBlock
}

/** Just enough Markdown for docs/licenses.md: headings, paragraphs, tables; inline code marks dropped. */
object LicenceText {
    fun parse(md: String): List<LicenceBlock> {
        val out = ArrayList<LicenceBlock>()
        val para = StringBuilder()
        var header: List<String>? = null
        fun flush() { if (para.isNotEmpty()) { out += LicenceBlock.Paragraph(para.toString()); para.clear() } }
        fun clean(s: String) = s.replace("`", "").replace("**", "").trim()
        for (raw in md.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> { flush(); header = null }
                line.startsWith("#") -> { flush(); val level = line.takeWhile { it == '#' }.length; out += LicenceBlock.Heading(clean(line.drop(level)), level) }
                line.startsWith("|") -> {
                    flush()
                    val cells = line.trim('|').split('|').map(::clean)
                    when {
                        cells.all { c -> c.isNotEmpty() && c.all { it == '-' || it == ':' } } -> Unit // the separator row
                        header == null -> header = cells
                        else -> out += LicenceBlock.Row(cells, header!!)
                    }
                }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(clean(line)) }
            }
        }
        flush()
        return out
    }
}
