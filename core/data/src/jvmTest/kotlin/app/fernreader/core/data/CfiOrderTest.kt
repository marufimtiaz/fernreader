package app.fernreader.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CfiOrderTest {
    private fun lt(a: String, b: String) = assertTrue(CfiOrder.compare(a, b) < 0, "$a < $b")

    @Test fun offsets_compare_numerically() {
        lt("epubcfi(/6/4!/4/2/1:9)", "epubcfi(/6/4!/4/2/1:10)")
    }

    @Test fun steps_compare_numerically() {
        lt("epubcfi(/6/4!/4/2)", "epubcfi(/6/4!/4/10)")
        lt("epubcfi(/6/4!/4/2)", "epubcfi(/6/10!/4/2)")
    }

    @Test fun ids_are_ignored() {
        assertEquals(0, CfiOrder.compare("epubcfi(/6/4[chap]!/4/2)", "epubcfi(/6/4!/4/2)"))
    }

    @Test fun a_prefix_comes_first() {
        lt("epubcfi(/6/4!/4)", "epubcfi(/6/4!/4/2)")
    }

    @Test fun range_starts_at_parent_plus_start() {
        assertEquals(0, CfiOrder.compare("epubcfi(/6/4!/4/2,/1:3,/1:8)", "epubcfi(/6/4!/4/2/1:3)"))
        lt("epubcfi(/6/4!/4/2,/1:3,/1:8)", "epubcfi(/6/4!/4/2,/1:5,/1:8)")
        lt("epubcfi(/6/4!/4/2,/1:3,/1:8)", "epubcfi(/6/4!/4/2/1:4)")
    }

    @Test fun malformed_sorts_after_valid_and_equal_to_each_other() {
        lt("epubcfi(/6/99!/4/2)", "garbage")
        lt("epubcfi(/6/99!/4/2)", "")
        assertEquals(0, CfiOrder.compare("x", "y"))
    }

    @Test fun without_the_wrapper_also_parses() {
        lt("/6/4!/4/2", "/6/4!/4/10")
    }
}
