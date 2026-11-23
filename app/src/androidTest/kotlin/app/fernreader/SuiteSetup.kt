package app.fernreader

import android.util.Log
import androidx.test.uiautomator.Configurator
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/**
 * Run-wide test settings (C-049), registered for every run through the test manifest's `listener` meta-data, so a
 * plain `am instrument` gets it as well as Gradle.
 *
 * UiAutomator waits before every query and action until the accessibility event stream has been quiet for 500 ms
 * (up to Configurator's 10 s idle timeout). The reader's WebView keeps that stream busy, so each findObject/wait cost
 * 0.5-1.1 s. Tests already wait for what they need (Until conditions, reader events), so the idle wait is skipped.
 */
class SuiteSetup : RunListener() {
    override fun testRunStarted(description: Description?) {
        Configurator.getInstance().setWaitForIdleTimeout(0)
        Log.i("EPR", "SuiteSetup: UiAutomator idle wait off")
    }
}
