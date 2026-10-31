package app.epubreader.reader

/**
 * F-060: the colour row shown after Highlight or Underline from the selection menu: the new annotation ([annotationId])
 * has [color]; picking another colour recolours it (all six colours within two taps) and makes it the next default.
 */
data class HighlightPicker(val annotationId: String, val color: String)

/** F-051: the selected text as a search query: trimmed, whitespace runs as one space, at most [SEARCH_CAP] chars. */
fun selectionQuery(text: String): String = text.trim().replace(WHITESPACE, " ").take(SEARCH_CAP).trimEnd()

/** The quoted text of a new annotation (for the list): the selection with its whitespace collapsed, trimmed. */
fun annotationText(text: String): String = text.trim().replace(WHITESPACE, " ")

const val SEARCH_CAP = 200
private val WHITESPACE = Regex("\\s+")
