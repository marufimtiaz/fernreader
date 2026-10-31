package app.epubreader.core.epub

import kotlin.test.Test
import kotlin.test.assertEquals

class PositionMapTest {
    @Test fun identity_maps_text_positions_to_themselves() {
        val p = TextPos(7, 1234)
        assertEquals(p, PositionMap.Identity.textToVirtual(p))
        assertEquals(p, PositionMap.Identity.textToOriginal(p))
    }
}
