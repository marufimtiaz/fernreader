package app.epubreader.reader.display

import app.epubreader.core.data.ReaderSettings

/** C-109: what the reader's brightness slider needs to know of the phone's brightness. Values are linear, 0..1. */
interface ScreenBrightness {
    /** The panel's lowest brightness, where the system slider starts; the reader's slider starts there too. */
    val min: Double

    /** The system's brightness now, or null when it can't be read. */
    fun current(): Double?

    companion object {
        /** Desktop and tests: no system slider to match. */
        val Unknown: ScreenBrightness = object : ScreenBrightness {
            override val min = ReaderSettings.MIN_BRIGHTNESS
            override fun current(): Double? = null
        }
    }
}
