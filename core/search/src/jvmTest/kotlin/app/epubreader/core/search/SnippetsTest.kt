package app.epubreader.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnippetsTest {
    @Test fun short_text_is_whole_with_hit_range() {
        val t = "The battle of Borodino was fought"
        val s = Snippets.build(t, 14, 22)
        assertEquals(t, s.text)
        assertEquals("Borodino", s.text.substring(s.hitStart, s.hitEnd))
    }

    @Test fun long_text_is_cut_at_word_boundaries_with_ellipses() {
        val words = (1..200).joinToString(" ") { "word$it" }
        val i = words.indexOf("word100")
        val s = Snippets.build(words, i, i + 7)
        assertTrue(s.text.startsWith("…") && s.text.endsWith("…"), s.text)
        assertEquals("word100", s.text.substring(s.hitStart, s.hitEnd))
        assertTrue(s.text.length in 150..230, "${s.text.length}")
        assertFalse(s.text.drop(1).startsWith("ord"), s.text) // not cut inside a word
        assertTrue(s.text.dropLast(1).split(' ').all { it.startsWith("word") || it.startsWith("…word") }, s.text)
    }

    @Test fun whitespace_runs_collapse_and_range_follows() {
        val t = "alpha \n\n  beta  \t gamma   delta"
        val s = Snippets.build(t, t.indexOf("gamma"), t.indexOf("gamma") + 5)
        assertEquals("alpha beta gamma delta", s.text)
        assertEquals("gamma", s.text.substring(s.hitStart, s.hitEnd))
    }

    @Test fun hit_at_start_and_end_have_no_ellipsis_on_that_side() {
        val words = (1..200).joinToString(" ") { "w$it" }
        val a = Snippets.build(words, 0, 2)
        assertFalse(a.text.startsWith("…")); assertTrue(a.text.endsWith("…"))
        val e = Snippets.build(words, words.length - 4, words.length)
        assertTrue(e.text.startsWith("…")); assertFalse(e.text.endsWith("…"))
        assertEquals("w200", e.text.substring(e.hitStart, e.hitEnd))
    }
}
