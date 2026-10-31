package app.epubreader.core.search

import androidx.sqlite.SQLiteConnection

internal class MatchRow(val rowid: Long, val spine: Int, val start: Int, val marked: String)

/** The paged FTS5 query and the conversion of its rows into hits. */
internal object QueryRunner {
    const val PAGE = 200
    /** The first page is small so the first results show at once (P-040): a common word has 1000+ hits in 200 rows. */
    const val FIRST_PAGE = 20

    /** Up to [limit] matching rows with rowid > [after], in book order (keyset paging). */
    fun page(db: SQLiteConnection, match: String, after: Long, limit: Int = PAGE): List<MatchRow> =
        db.prepare("SELECT rowid, spine, start, highlight(seg, 0, char(1), char(2)) FROM seg WHERE seg MATCH ? AND rowid > ? ORDER BY rowid LIMIT $limit").use { st ->
            st.bindText(1, match); st.bindLong(2, after)
            buildList { while (st.step()) add(MatchRow(st.getLong(0), st.getLong(1).toInt(), st.getLong(2).toInt(), st.getText(3))) }
        }

    /** Each marked span of the row is a hit; offsets are made absolute with the segment's start. */
    fun hitsOf(row: MatchRow): List<SearchHit> {
        val h = Highlights.parse(row.marked)
        return List(h.starts.size) { i ->
            val s = Snippets.build(h.plain, h.starts[i], h.ends[i])
            SearchHit(row.spine, row.start + h.starts[i], h.ends[i] - h.starts[i], s.text, s.hitStart, s.hitEnd, h.plain.substring(h.starts[i], h.ends[i]))
        }
    }
}
