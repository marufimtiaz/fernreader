package app.epubreader.reader

import app.epubreader.ui.Copy
import kotlin.test.Test
import kotlin.test.assertEquals

/** C-048 (R-002, R-003): package problems get a clear detail line, never the engine's raw script message. */
class ReaderErrorTextTest {
    @Test fun no_package_is_worded() =
        assertEquals("No package found in this file.", Copy.engineDetail(ReaderError.Engine("no_package", "Failed to load container file")))

    @Test fun empty_spine_is_worded() =
        assertEquals("This book has no readable sections.", Copy.engineDetail(ReaderError.Engine("nothing_readable", "Cannot destructure property 'parentNode'")))

    @Test fun other_engine_failures_keep_their_message() =
        assertEquals("XML parse error", Copy.engineDetail(ReaderError.Engine("open_failed", "XML parse error")))
}
