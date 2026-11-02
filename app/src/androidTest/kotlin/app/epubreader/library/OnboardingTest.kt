package app.epubreader.library

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.epubreader.MainActivity
import app.epubreader.ui.Copy
import app.epubreader.ui.Route
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/** F-001: explanation before any library UI; skipped when pre-granted; granting shows the library without a restart. */
@RunWith(AndroidJUnit4::class)
class OnboardingTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(inst)

    @Volatile private var granted = false
    private fun access(mode: String) { granted = mode == "allow"; StorageAccess.grantedOverride = { granted } }

    @After fun restore() { StorageAccess.grantedOverride = null }

    @Test fun denied_shows_explanation_first() {
        access("deny")
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            assertNotNull(device.wait(Until.findObject(By.text(Copy.onboardingTitle)), 5_000))
            sc.onActivity { assertEquals(Route.Onboarding, it.nav.top) }
        }
    }

    @Test fun pregranted_skips_explanation() {
        access("allow")
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            assertNotNull(device.wait(Until.findObject(By.text(Copy.allBooks)), 5_000))
            sc.onActivity { assertEquals(Route.Library, it.nav.top) }
            assertEquals(false, device.hasObject(By.text(Copy.onboardingTitle)))
        }
    }

    @Test fun grant_while_running_shows_library_without_restart() {
        access("deny")
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            assertNotNull(device.wait(Until.findObject(By.text(Copy.onboardingTitle)), 5_000))
            lateinit var before: MainActivity
            var scanned = false
            sc.onActivity { before = it; it.onAccessGranted = { scanned = true } }
            access("allow")
            sc.moveToState(Lifecycle.State.STARTED) // leave and come back, as from the system settings screen
            sc.moveToState(Lifecycle.State.RESUMED)
            assertNotNull(device.wait(Until.findObject(By.text(Copy.allBooks)), 5_000))
            sc.onActivity { assertSame(before, it); assertEquals(Route.Library, it.nav.top) }
            assertEquals(true, scanned)
        }
    }
}
