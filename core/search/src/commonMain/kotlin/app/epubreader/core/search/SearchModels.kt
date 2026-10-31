package app.epubreader.core.search

/** F-050: at most this many hits are reported; a search that reaches it is `capped` ("5000+"). */
const val DEFAULT_SEARCH_CAP = 5000

/** [wholeWord] = exact words only; otherwise words that start with the text (C-001). */
data class SearchQuery(val text: String, val wholeWord: Boolean = false, val cap: Int = DEFAULT_SEARCH_CAP)

/**
 * One match: [length] characters at [offset] in the canonical text of spine item [spineIndex]
 * (`TextPos(spineIndex, offset)`). [snippet] is ~200 characters around it; the hit is `snippetHitStart until snippetHitEnd`.
 */
data class SearchHit(
    val spineIndex: Int,
    val offset: Int,
    val length: Int,
    val snippet: String,
    val snippetHitStart: Int,
    val snippetHitEnd: Int,
)

/** [hits] are this batch only; [total] counts every hit so far. The last update has `done = true`. */
data class SearchUpdate(val hits: List<SearchHit>, val total: Int, val capped: Boolean, val done: Boolean)

data class IndexProgress(val sectionsDone: Int, val sections: Int, val complete: Boolean, val running: Boolean, val failed: Boolean)
