package app.epubreader.reader.display

import app.epubreader.core.data.ReaderSettings
import app.epubreader.ui.Copy
import kotlin.test.Test
import kotlin.test.assertEquals

/** C-110: the Extra dim slider runs from light dimming (70 %) at its start to strong (15 %) at its end. */
class ExtraDimRowTest {
    @Test fun the_slider_runs_from_70_down_to_15_percent() {
        assertEquals(0.7, dimLevel(0f))
        assertEquals(0.15, dimLevel(1f))
        assertEquals(0.43, dimLevel(0.5f)) // 0.425 to the hundredth
        assertEquals(0f, dimPosition(ReaderSettings.DIM_LIGHT))
        assertEquals(1f, dimPosition(ReaderSettings.DIM_STRONG))
    }

    @Test fun position_and_level_round_trip_and_stay_in_range() {
        for (level in listOf(0.7, 0.62, 0.5, 0.31, 0.15)) assertEquals(level, dimLevel(dimPosition(level)))
        assertEquals(0.7, dimLevel(-1f))
        assertEquals(0.15, dimLevel(2f))
        assertEquals(1f, dimPosition(0.0))
    }

    @Test fun the_value_shown_is_the_page_level() {
        assertEquals("50 %", Copy.extraDimLevel(ReaderSettings.DIM_DEFAULT))
        assertEquals("15 %", Copy.extraDimLevel(0.15))
        assertEquals("Extra dim", Copy.extraDim)
    }
}
