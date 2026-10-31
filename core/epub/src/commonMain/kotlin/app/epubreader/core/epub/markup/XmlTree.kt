package app.epubreader.core.epub.markup

class XmlElement(val name: String, val attrs: List<Attr>, val start: Int) {
    val children = ArrayList<Any>(4) // XmlElement or String (decoded text)
    val localName: String get() = name.substringAfter(':').lowercase()
    fun attr(local: String): String? = attrs.firstOrNull { it.localName.equals(local, ignoreCase = true) }?.value
    /** Attribute by qualified name, e.g. "epub:type". */
    fun qattr(qname: String): String? = attrs.firstOrNull { it.name.equals(qname, ignoreCase = true) }?.value
    fun elements(): List<XmlElement> = children.filterIsInstance<XmlElement>()
    fun descendants(): Sequence<XmlElement> = sequence {
        for (c in children) if (c is XmlElement) { yield(c); yieldAll(c.descendants()) }
    }
    fun first(local: String): XmlElement? = descendants().firstOrNull { it.localName == local }
    fun all(local: String): Sequence<XmlElement> = descendants().filter { it.localName == local }
    fun text(): String = buildString { fun walk(e: XmlElement) { for (c in e.children) if (c is String) append(c) else walk(c as XmlElement) }; walk(this@XmlElement) }
}

class XmlDocument(val root: XmlElement?, val errors: List<String>) {
    val wellFormed: Boolean get() = root != null && errors.isEmpty()
}

/** Small-document tree builder (package, container, NCX, nav). Lenient: always returns the best tree it can, plus errors. */
object XmlTree {
    fun parse(s: String, html: Boolean = false): XmlDocument {
        val errors = ArrayList<String>()
        val doc = XmlElement("#document", emptyList(), 0)
        val stack = ArrayList<XmlElement>().apply { add(doc) }
        MarkupScanner.scan(s, object : MarkupVisitor {
            override fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) {
                val e = XmlElement(name, attrs, start)
                if (stack.size == 1 && doc.elements().isNotEmpty()) errors += "multiple root elements"
                stack.last().children += e
                val void = html && name.lowercase() in HTML_VOID
                if (!selfClosing && !void) stack += e
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
