package app.epubreader.reader

/**
 * F-061: the sheet of a tapped highlight or underline: its quoted [text], [color] and [style] as now shown, and the
 * [note] draft as typed (saved, trimmed, when the sheet closes; blank = no note).
 */
data class AnnotationSheet(val id: String, val text: String, val color: String, val style: String, val note: String)

/** The note as stored: trimmed; blank is no note (null). */
fun noteToStore(draft: String): String? = draft.trim().takeIf { it.isNotEmpty() }
