package app.epubreader.reader

import android.content.ContentResolver
import android.content.res.Resources
import android.os.Build
import android.provider.Settings
import app.epubreader.core.data.ReaderSettings
import app.epubreader.reader.display.ScreenBrightness

/**
 * C-109: the phone's brightness on the scale the window takes (`WindowManager.LayoutParams.screenBrightness`, linear
 * 0..1), read from public settings and the framework's own configuration.
 *
 * - Current: `screen_brightness_float` (Android 11+, the value behind the system slider), else the public int `screen_brightness`, 1..255 on Android 11+ (1 = the lowest
 *   float, AOSP BrightnessSynchronizer) and 0..255 before.
 * - Minimum: the framework's `config_screenBrightnessSettingMinimumFloat` (Android 11+), else its int counterpart; the
 *   system slider starts there.
 */
class SystemBrightness(private val resolver: ContentResolver) : ScreenBrightness {
    override val min: Double by lazy { app.epubreader.ui.traced("Brightness:min") { (configMin() ?: DEFAULT_MIN).coerceIn(ReaderSettings.MIN_BRIGHTNESS, 0.5) } }

    override fun current(): Double? = app.epubreader.ui.traced("Brightness:current") { currentNow() }

    private fun currentNow(): Double? = runCatching {
        floatSetting() ?: Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS).let(::fromInt)
    }.getOrNull()?.coerceIn(min, 1.0)

    private fun floatSetting(): Double? = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) null else
        runCatching { Settings.System.getFloat(resolver, "screen_brightness_float").toDouble() }.getOrNull()?.takeIf { it in 0.0..1.0 }

    private fun configMin(): Double? = runCatching {
        val res = Resources.getSystem()
        val f = res.getIdentifier("config_screenBrightnessSettingMinimumFloat", "dimen", "android")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && f != 0) res.getFloat(f).toDouble().takeIf { it > 0.0 && it < 1.0 }?.let { return@runCatching it }
        val i = res.getIdentifier("config_screenBrightnessSettingMinimum", "integer", "android")
        if (i != 0) fromInt(res.getInteger(i)) else null
    }.getOrNull()

    companion object {
        /** AOSP's default minimum, 10 of 255, when the framework's configuration can't be read. */
        private val DEFAULT_MIN = fromInt(10)

        /** The public int setting as the window's float (AOSP BrightnessSynchronizer on Android 11+). */
        fun fromInt(v: Int, sdk: Int = Build.VERSION.SDK_INT): Double =
            if (sdk >= Build.VERSION_CODES.R) ((v.coerceIn(1, 255) - 1) / 254.0) else v.coerceIn(0, 255) / 255.0
    }
}
