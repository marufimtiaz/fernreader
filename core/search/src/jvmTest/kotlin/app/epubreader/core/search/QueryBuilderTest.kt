package app.epubreader.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueryBuilderTest {
    private fun prefix(t: String) = QueryBuilder.build(SearchQuery(t))
    private fun whole(t: String) = QueryBuilder.build(SearchQuery(t, wholeWord = true))

    @Test fun apostrophe_and_hyphen() {
        assertEquals("\"prince s\"*", prefix("prince's"))
        assertEquals("\"Prince s\"*", prefix("Prince\u2019s")) // case is left to FTS5
        assertEquals("\"well known\"*", prefix("well-known"))
        assertEquals("\"well known\"", whole("well-known"))
    }

    @Test fun single_word_modes() {
        assertEquals("\"Borodino\"*", prefix("Borodino"))
        assertEquals("\"Borodino\"", whole("Borodino"))
        assertEquals("\"Borodinó\"*", prefix("Borodinó"))
    }

    @Test fun multi_word_is_one_phrase() = assertEquals("\"augmented matrix\"*", prefix("  augmented   matrix "))

    @Test fun fts_syntax_never_reaches_match() {
        assertEquals("\"a OR b NEAR c\"*", prefix("a OR b NEAR(c)"))
        assertEquals("\"x y\"", whole("x\" * y"))
        assertEquals("\"col text\"", whole("col:text"))
    }

    @Test fun digits_and_combining_marks_are_token_characters() {
        assertEquals("\"abc123\"", whole("abc123"))
        assertEquals("\"été\"", whole("été"))
    }

    @Test fun no_tokens_gives_null() {
        assertNull(prefix(""))
        assertNull(prefix(" ’-- \"*"))
    }
}
