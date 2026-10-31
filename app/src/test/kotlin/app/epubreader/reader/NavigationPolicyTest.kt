package app.epubreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Minor 2 (R-007 defense in depth): navigation stays on our https origin; book responses forbid scripts. */
class NavigationPolicyTest {
    private val host = ReaderEngine.ORIGIN_HOST

    @Test fun main_frame_needs_https_and_our_host() {
        assertTrue(allowNavigation("https", host, isMainFrame = true))
        assertFalse(allowNavigation("http", host, isMainFrame = true))
        assertFalse(allowNavigation("https", "example.com", isMainFrame = true))
        assertFalse(allowNavigation("blob", host, isMainFrame = true))
        assertFalse(allowNavigation("javascript", null, isMainFrame = true))
    }

    @Test fun subframes_may_be_blob_or_our_https_origin() {
        assertTrue(allowNavigation("blob", null, isMainFrame = false))
        assertTrue(allowNavigation("https", host, isMainFrame = false))
        assertFalse(allowNavigation("http", host, isMainFrame = false))
        assertFalse(allowNavigation("https", "example.com", isMainFrame = false))
    }

    @Test fun book_responses_carry_a_no_script_csp() {
        assertEquals("script-src 'none'; object-src 'none'", BOOK_RESPONSE_HEADERS["Content-Security-Policy"])
        assertEquals("no-store", BOOK_RESPONSE_HEADERS["Cache-Control"])
    }
}
