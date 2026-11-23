package app.fernreader.core.epub.markup

class XmlElement(val name: String, val attrs: List<Attr>, val start: Int) {
    val children = ArrayList<Any>(4) // XmlElement or String (decoded text)
    val localName: String get() = name.substringAfter(':').lowercase()
    fun attr(local: String): String? = attrs.firstOrNull { it.localName.equals(local, ignoreCase = true) }?.value
    /** Attribute by qualified name, e.g. "epub:type". */
    fun qattr(qname: String): String? = attrs.firstOrNull { it.name.equals(qname, ignoreCase = true) }?.value
    fun elements(): List<XmlElement> = children.filterIsInstance<XmlElement>()
    /** Document order, iterative (a hostile document must not exhaust the stack). */
    fun descendants(): Sequence<XmlElement> = sequence {
        val stack = ArrayDeque<Iterator<Any>>().apply { add(children.iterator()) }
        while (stack.isNotEmpty()) {
            val it = stack.last()
            if (!it.hasNext()) { stack.removeLast(); continue }
            val c = it.next()
            if (c is XmlElement) { yield(c); stack.addLast(c.children.iterator()) }
        }
    }
    fun first(local: String): XmlElement? = descendants().firstOrNull { it.localName == local }
    fun all(local: String): Sequence<XmlElement> = descendants().filter { it.localName == local }
    fun text(): String = buildString {
        val stack = ArrayDeque<Iterator<Any>>().apply { add(children.iterator()) }
        while (stack.isNotEmpty()) {
            val it = stack.last()
            if (!it.hasNext()) { stack.removeLast(); continue }
            when (val c = it.next()) { is String -> append(c); is XmlElement -> stack.addLast(c.children.iterator()) }
        }
    }
}

class XmlDocument(val root: XmlElement?, val errors: List<String>) {
    val wellFormed: Boolean get() = root != null && errors.isEmpty()
}

/**
 * Small-document tree builder (package, container, NCX, nav). Lenient: always returns the best tree it can, plus errors.
 * Bounded for hostile input (R-002, R-006): nesting deeper than [MAX_DEPTH] is flattened, elements past
 * [MAX_ELEMENTS] are dropped, and at most [MAX_ERRORS] errors are kept; each limit hit is reported as an error.
 */
object XmlTree {
    const val MAX_DEPTH = 256
    const val MAX_ELEMENTS = 200_000
    const val MAX_ERRORS = 100

    fun parse(s: String, html: Boolean = false): XmlDocument {
        val errors = object : ArrayList<String>() {
            override fun add(element: String): Boolean = if (size < MAX_ERRORS) super.add(element) else false
        }
        var count = 0
        var tooDeep = false
        val doc = XmlElement("#document", emptyList(), 0)
        val stack = ArrayList<XmlElement>().apply { add(doc) }
        MarkupScanner.scan(s, object : MarkupVisitor {
            override fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) {
                if (++count > MAX_ELEMENTS) { if (count == MAX_ELEMENTS + 1) errors += "more than $MAX_ELEMENTS elements"; return }
                val e = XmlElement(name, attrs, start)
                if (stack.size == 1 && doc.children.any { it is XmlElement }) errors += "multiple root elements"
                stack.last().children += e
                val void = html && name.lowercase() in HTML_VOID
                if (selfClosing || void) return
                if (stack.size > MAX_DEPTH) { if (!tooDeep) { tooDeep = true; errors += "nested deeper than $MAX_DEPTH" }; return }
                stack += e
            }
            override fun endTag(name: String, start: Int, end: Int) {
                if (html && name.lowercase() in HTML_VOID) return
                val idx = stack.indexOfLast { it.name.equals(name, ignoreCase = html) }
                when {
                    idx <= 0 -> errors += "stray </$name> at $start"
                    idx == stack.size - 1 -> stack.removeAt(idx)
                    else -> { errors += "mismatched </$name> at $start"; while (stack.size > idx) stack.removeAt(stack.size - 1) }
                }
            }
            override fun text(start: Int, end: Int) {
                if (stack.size == 1) { if (s.substring(start, end).isNotBlank()) errors += "text outside root"; return }
                stack.last().children += Entities.decode(s.substring(start, end))
            }
            override fun cdata(start: Int, end: Int) { stack.last().children += s.substring(start, end) }
            override fun error(message: String, at: Int) { errors += "$message at $at" }
        })
        if (stack.size > 1) errors += "unclosed <${stack.last().name}>"
        return XmlDocument(doc.elements().firstOrNull(), errors)
    }
}
