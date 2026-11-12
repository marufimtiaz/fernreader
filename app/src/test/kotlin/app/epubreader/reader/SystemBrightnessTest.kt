package app.epubreader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** C-109: the public int setting read as the window's float, as the platform converts it. */
class SystemBrightnessTest {
    @Test fun android_11_and_later_map_1_to_255_onto_0_to_1() {
        assertEquals(0.0, SystemBrightness.fromInt(1, sdk = 30), 0.0)
        assertEquals(1.0, SystemBrightness.fromInt(255, sdk = 34), 0.0)
        assertEquals(99 / 254.0, SystemBrightness.fromInt(100, sdk = 30), 1e-12)
        assertEquals(0.0, SystemBrightness.fromInt(0, sdk = 30), 0.0) // 0 is "off" there; never below the lowest
    }

    @Test fun older_versions_map_0_to_255_onto_0_to_1() {
        assertEquals(1.0, SystemBrightness.fromInt(255, sdk = 29), 0.0)
        assertEquals(100 / 255.0, SystemBrightness.fromInt(100, sdk = 26), 1e-12)
    }
}
