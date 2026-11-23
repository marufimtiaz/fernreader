package app.fernreader.reader

import app.fernreader.core.data.SwipeDirection
import app.fernreader.core.data.VolumeKeys

/** Page-turn inputs decided in Kotlin (F-032): tap zones and volume keys. Swipes are recognised on the web side. */
enum class PageTurn { Next, Prev }

enum class TapAction { Next, Prev, Controls, None }

/** Outer share of the width at each side that turns pages when tap zones are on. */
const val TAP_EDGE = 0.25

/**
 * A tap on the page at [x] (0..1 of the width). The centre (0.33..0.67) toggles the controls; with [tapZones] the outer
 * 25 % turn pages, mirrored like swipes for [rtl] (left edge = next). Everything else does nothing.
 */
fun tapAction(x: Double, tapZones: Boolean, rtl: Boolean): TapAction = when {
    x in 0.33..0.67 -> TapAction.Controls
    !tapZones -> TapAction.None
    x < TAP_EDGE -> if (rtl) TapAction.Next else TapAction.Prev
    x > 1 - TAP_EDGE -> if (rtl) TapAction.Prev else TapAction.Next
    else -> TapAction.None
}

/** C-005: ON = volume-down next, volume-up previous; REVERSED swaps; OFF = null (the key keeps its normal meaning). */
fun volumeTurn(volumeDown: Boolean, mode: VolumeKeys): PageTurn? = when (mode) {
    VolumeKeys.OFF -> null
    VolumeKeys.ON -> if (volumeDown) PageTurn.Next else PageTurn.Prev
    VolumeKeys.REVERSED -> if (volumeDown) PageTurn.Prev else PageTurn.Next
}

/** The page direction in effect: the book's page-progression-direction ([bookDir], from `opened`) for AUTO. */
fun effectiveRtl(setting: SwipeDirection, bookDir: String?): Boolean = when (setting) {
    SwipeDirection.AUTO -> bookDir == "rtl"
    SwipeDirection.LTR -> false
    SwipeDirection.RTL -> true
}
