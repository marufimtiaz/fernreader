package app.fernreader.core.data

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * C-109: the system brightness slider's perceptual scale, so the reader's slider gives the same screen brightness as
 * the system's at the same position. A slider position (0..1) maps to a window brightness between the panel's
 * [min] and [max] (linear, as `WindowManager.LayoutParams.screenBrightness` takes it).
 *
 * The curve is the one AOSP's SettingsLib uses for the system slider (`BrightnessUtils`, Android 9 and later): the
 * hybrid log-gamma transfer function, square law over the lower half of the slider, logarithmic above, which puts most
 * of the slider's travel in the dim range. Position 0.5 is 1/12 of the range above [min].
 */
object BrightnessCurve {
    private const val R = 0.5
    private const val A = 0.17883277
    private const val B = 0.28466892
    private const val C = 0.55991073

    /** The window brightness for slider [position]; the slider's end is exactly [max]. */
    fun toLinear(position: Double, min: Double, max: Double): Double {
        val p = position.coerceIn(0.0, 1.0)
        if (p >= 1.0) return max // the curve's top is 11.996/12: the end is the panel's full brightness (C-109)
        val hlg = if (p <= R) (p / R) * (p / R) else exp((p - C) / A) + B
        return min + (max - min) * (hlg / 12).coerceIn(0.0, 1.0)
    }

    /** The slider position that gives window brightness [linear]; the inverse of [toLinear]. */
    fun toPosition(linear: Double, min: Double, max: Double): Double {
        if (max <= min || linear >= max) return 1.0 // full brightness is the slider's end, as in [toLinear]
        val n = ((linear - min) / (max - min)).coerceIn(0.0, 1.0) * 12
        val p = if (n <= 1.0) sqrt(n) * R else A * ln(n - B) + C
        return p.coerceIn(0.0, 1.0)
    }
}
