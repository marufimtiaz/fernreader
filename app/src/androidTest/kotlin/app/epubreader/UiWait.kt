package app.epubreader

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * Finds [selector] and waits until the node stands still on screen (same bounds, inside the display, two reads 80 ms
 * apart), for a click on something that animates in, like the floating selection toolbar. SuiteSetup turns off
 * UiAutomator's idle wait (C-049), which used to hold such clicks until the animation ended; without it a click
 * landed on the toolbar while it was still sliding in (HighlightsTest, full run).
 */
fun UiDevice.settled(selector: BySelector, timeoutMs: Long): UiObject2? {
    val end = SystemClock.uptimeMillis() + timeoutMs
    val screen = Rect(0, 0, displayWidth, displayHeight)
    var last: Rect? = null
    while (SystemClock.uptimeMillis() < end) {
        val o = wait(Until.findObject(selector), (end - SystemClock.uptimeMillis()).coerceAtLeast(1)) ?: return null
        val b = runCatching { o.visibleBounds }.getOrNull()
        if (b != null && !b.isEmpty && screen.contains(b) && b == last) return o
        last = b
        SystemClock.sleep(80)
    }
    return null
}

/**
 * Polls [check] every 50 ms for up to [timeoutMs], clearing UiAutomator's accessibility cache before each poll. On
 * API 34+ that cache can keep a Compose screen's old nodes after its content changed (a list that got a new first
 * item, a drawer folder that unfolded), and `wait(Until…)` then sees the old screen until it gives up.
 */
fun waitFresh(timeoutMs: Long, check: () -> Boolean): Boolean {
    val ua = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
    val end = SystemClock.uptimeMillis() + timeoutMs
    while (true) {
        if (android.os.Build.VERSION.SDK_INT >= 34) ua.clearCache()
        if (check()) return true
        if (SystemClock.uptimeMillis() >= end) return false
        SystemClock.sleep(50)
    }
}
