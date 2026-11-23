package app.fernreader.ui

/**
 * Named sections for a system trace (Perfetto, atrace): the app sets [begin] and [end] to the platform's trace calls; null
 * elsewhere (desktop, host tests), where a section costs a null check. Sections must be balanced on one thread.
 */
object Sections {
    var begin: ((String) -> Unit)? = null
    var end: (() -> Unit)? = null
}

/** Runs [block] inside the trace section [name]. */
inline fun <T> traced(name: String, block: () -> T): T {
    val b = Sections.begin ?: return block()
    b(name)
    try { return block() } finally { Sections.end?.invoke() }
}

/** Opens the section [name]; for code that can't use [traced] (around composable calls). Close it with [sectionEnd]. */
fun sectionStart(name: String) { Sections.begin?.invoke(name) }
fun sectionEnd() { if (Sections.begin != null) Sections.end?.invoke() }
